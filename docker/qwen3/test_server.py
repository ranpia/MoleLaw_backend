"""Offline HTTP contract checks; real model correctness is checked separately."""
import importlib.util
import json
import pathlib
import threading
import types
import unittest
import urllib.error
import urllib.request
from unittest.mock import MagicMock, patch


class Encoder:
    max_seq_length = 8192

    def tokenizer(self, texts, **kwargs):
        return {"length": [len(text) for text in texts]}

    def encode(self, texts, **kwargs):
        return types.SimpleNamespace(tolist=lambda: [[1.0] + [0.0] * 1023 for _ in texts])


class ContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("qwen3_adapter", pathlib.Path(__file__).with_name("server.py"))
        cls.adapter = importlib.util.module_from_spec(spec)
        with patch.dict("sys.modules", {"torch": MagicMock(), "sentence_transformers": MagicMock()}), patch("importlib.metadata.version", return_value="test"):
            spec.loader.exec_module(cls.adapter)
        cls.server = cls.adapter.HTTPServer(("127.0.0.1", 0), cls.adapter.Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)

    def setUp(self):
        self.adapter.model = Encoder()

    def request(self, path, payload=None):
        request = urllib.request.Request(self.url + path, data=None if payload is None else json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)

    def payload(self, **updates):
        result = {"model": self.adapter.TAG, "input": ["법령 질문"], "truncate": False, "options": {"num_thread": 6, "num_gpu": 0}}
        result.update(updates)
        return result

    def test_embedding_contract(self):
        status, body = self.request("/api/embed", self.payload())
        self.assertEqual(status, 200)
        self.assertEqual(len(body["embeddings"]), 1)
        self.assertEqual(len(body["embeddings"][0]), 1024)

    def test_model_swap_rejected(self):
        self.assertEqual(self.request("/api/embed", self.payload(model="other"))[0], 400)

    def test_truncation_and_gpu_requests_rejected(self):
        self.assertEqual(self.request("/api/embed", self.payload(truncate=True))[0], 400)
        self.assertEqual(self.request("/api/embed", self.payload(options={"num_thread": 6, "num_gpu": 1}))[0], 400)

    def test_long_input_rejected(self):
        self.assertEqual(self.request("/api/embed", self.payload(input=["x" * 8193]))[0], 400)

    def test_invalid_batch_rejected(self):
        self.assertEqual(self.request("/api/embed", self.payload(input=["x"] * 33))[0], 400)
        self.assertEqual(self.request("/api/embed", self.payload(input=[None]))[0], 400)

    def test_unload_and_loaded_state(self):
        self.assertEqual(len(self.request("/api/ps")[1]["models"]), 1)
        self.assertEqual(self.request("/api/embed", self.payload(input=[], keep_alive=0))[0], 200)
        self.assertEqual(self.request("/api/ps")[1]["models"], [])

    def test_revision_and_runtime_metadata(self):
        self.assertEqual(self.request("/api/tags")[1]["models"][0]["digest"], self.adapter.REVISION)
        self.assertEqual(self.request("/api/version")[1]["runtime"], "sentence-transformers")
        self.assertEqual(self.request("/unknown")[0], 404)


if __name__ == "__main__":
    unittest.main()
