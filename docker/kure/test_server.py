"""HTTP concurrency regression using a blocked fake encoder, never loading model files."""
import concurrent.futures
import json
import threading
import unittest
import urllib.error
import urllib.request
import server


class Encoder:
    max_seq_length = 8192

    def __init__(self):
        self.started = threading.Event()
        self.release = threading.Event()

    def tokenizer(self, inputs, **kwargs):
        return {"length": [1] * len(inputs)}

    def encode(self, inputs, **kwargs):
        self.started.set()
        if not self.release.wait(5):
            raise RuntimeError("Test did not release encoder")
        class Vector:
            def tolist(self):
                return [[0.0] * 1024]
        return Vector()


class ConcurrencyTest(unittest.TestCase):
    def test_queue_timeout_releases_admission_and_does_not_start_inference(self):
        encoder = Encoder()
        encoder.release.set()
        old_model, old_wait, old_admission = server.model, server.QUEUE_WAIT_SECONDS, server.ADMISSION
        server.model, server.QUEUE_WAIT_SECONDS = encoder, 0.05
        server.ADMISSION = threading.BoundedSemaphore(1)
        http = server.ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        threading.Thread(target=http.serve_forever, daemon=True).start()
        body = json.dumps({"model": server.TAG, "input": ["test"], "truncate": False,
                           "options": {"num_thread": 6, "num_gpu": 0}}).encode()
        request = urllib.request.Request(f"http://127.0.0.1:{http.server_port}/api/embed", data=body)
        try:
            server.INFERENCE_LOCK.acquire()
            try:
                with self.assertRaises(urllib.error.HTTPError) as rejected:
                    urllib.request.urlopen(request, timeout=2)
                self.assertEqual(rejected.exception.code, 503)
                self.assertFalse(encoder.started.is_set())
            finally:
                server.INFERENCE_LOCK.release()
            with urllib.request.urlopen(request, timeout=2) as response:
                self.assertEqual(response.status, 200)
        finally:
            http.shutdown()
            http.server_close()
            server.model, server.QUEUE_WAIT_SECONDS, server.ADMISSION = old_model, old_wait, old_admission

    def test_health_remains_responsive_and_saturated_inference_returns_503(self):
        encoder = Encoder()
        old_model, old_admission = server.model, server.ADMISSION
        server.model = encoder
        server.ADMISSION = threading.BoundedSemaphore(1)
        http = server.ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        worker = threading.Thread(target=http.serve_forever, daemon=True)
        worker.start()
        base = f"http://127.0.0.1:{http.server_port}"

        def embed():
            body = json.dumps({"model": server.TAG, "input": ["test"], "truncate": False,
                               "options": {"num_thread": 6, "num_gpu": 0}}).encode()
            return urllib.request.urlopen(urllib.request.Request(base + "/api/embed", data=body), timeout=4)

        try:
            with concurrent.futures.ThreadPoolExecutor() as pool:
                pending = pool.submit(embed)
                self.assertTrue(encoder.started.wait(2))
                with urllib.request.urlopen(base + "/api/version", timeout=1) as health:
                    self.assertEqual(health.status, 200)
                with self.assertRaises(urllib.error.HTTPError) as rejected:
                    embed()
                self.assertEqual(rejected.exception.code, 503)
                encoder.release.set()
                with pending.result(timeout=3) as completed:
                    self.assertEqual(completed.status, 200)
                    self.assertEqual(len(json.load(completed)["embeddings"][0]), 1024)
                # Releasing permits on success must allow the next inference.
                with embed() as next_response:
                    self.assertEqual(next_response.status, 200)
        finally:
            encoder.release.set()
            http.shutdown()
            http.server_close()
            server.model, server.ADMISSION = old_model, old_admission


if __name__ == "__main__":
    unittest.main()
