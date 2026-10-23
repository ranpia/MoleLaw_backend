"""KURE CPU adapter: concurrent health HTTP with bounded, single-flight inference.

Supports the repository's /api/embed contract, not a complete Ollama API.
"""
import gc
import os
import threading
import time
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from importlib.metadata import version

import torch
from sentence_transformers import SentenceTransformer

MODEL_ID = "nlpai-lab/KURE-v1"
REVISION = "8b418a58414668e75532ed045c22d9ca018ae2b2"
TAG = "kure-v1:official"
THREADS = 6
torch.set_num_threads(THREADS)
torch.set_num_interop_threads(1)
RUNTIME = ";".join(f"{name}/{version(name)}" for name in ("sentence-transformers", "torch", "transformers"))
model = None
# Health requests bypass this gate. CPU inference remains single-flight.
INFERENCE_LOCK = threading.Lock()
QUEUE_CAPACITY = int(os.environ.get("EMBEDDING_QUEUE_CAPACITY", "4"))
QUEUE_WAIT_SECONDS = float(os.environ.get("EMBEDDING_QUEUE_WAIT_SECONDS", "5"))
if not 0 <= QUEUE_CAPACITY <= 32 or not 0 < QUEUE_WAIT_SECONDS <= 120:
    raise ValueError("Invalid embedding queue configuration")
ADMISSION = threading.BoundedSemaphore(1 + QUEUE_CAPACITY)


def load_model():
    global model
    if model is None:
        model = SentenceTransformer(MODEL_ID, revision=REVISION, device="cpu", trust_remote_code=False)
        model.float()
        model.eval()
        if model.get_embedding_dimension() != 1024 or model.max_seq_length != 8192:
            raise RuntimeError("Unexpected official model dimensions/context limit")
        if model[1].get_config_dict().get("pooling_mode") != "cls":
            raise RuntimeError("Unexpected official CLS pooling configuration")
        if model[2].__class__.__name__ != "Normalize":
            raise RuntimeError("Unexpected official normalization module")
    return model


class Handler(BaseHTTPRequestHandler):
    def respond(self, status, payload):
        encoded = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)
        except (BrokenPipeError, ConnectionResetError):
            # A disconnected health client must not interrupt inference or flood logs.
            pass

    def do_GET(self):
        if self.path == "/api/version":
            self.respond(200, {"version": RUNTIME, "runtime": "sentence-transformers", "revision": REVISION, "dtype": "float32", "device": "cpu"})
        elif self.path == "/api/tags":
            self.respond(200, {"models": [{"name": TAG, "digest": REVISION}]})
        elif self.path == "/api/ps":
            self.respond(200, {"models": [{"name": TAG}] if model is not None else []})
        else:
            self.respond(404, {"error": "Unknown evaluation route"})

    def do_POST(self):
        global model
        admitted = False
        locked = False
        try:
            self.connection.settimeout(10)
            if self.path != "/api/embed":
                self.respond(404, {"error": "Unknown embedding route"})
                return
            if not ADMISSION.acquire(blocking=False):
                self.respond(503, {"error": "Embedding queue is full"})
                self.close_connection = True
                return
            admitted = True
            length = int(self.headers.get("Content-Length", "0"))
            if length < 1 or length > 1_048_576:
                raise ValueError("Invalid request size")
            payload = json.loads(self.rfile.read(length))
            if self.path != "/api/embed":
                self.respond(404, {"error": "Unknown evaluation route"})
                return
            if payload.get("model") != TAG:
                raise ValueError("Only the pinned official KURE-v1 model is supported")
            inputs = payload.get("input")
            if not isinstance(inputs, list) or len(inputs) > 32 or any(not isinstance(text, str) for text in inputs):
                raise ValueError("Expected at most 32 text inputs")
            # All operations touching the model, including unload, share the same lock.
            locked = INFERENCE_LOCK.acquire(timeout=QUEUE_WAIT_SECONDS)
            if not locked:
                self.respond(503, {"error": "Embedding queue wait expired"})
                return
            if not inputs and payload.get("keep_alive") == 0:
                model = None
                gc.collect()
                self.respond(200, {"model": TAG, "embeddings": []})
                return
            options = payload.get("options", {})
            if options.get("num_thread") != THREADS or options.get("num_gpu") != 0 or payload.get("truncate") is not False:
                raise ValueError("Evaluation requires 6 CPU threads, no GPU and truncate=false")
            encoder = load_model()
            lengths = encoder.tokenizer(inputs, truncation=False, add_special_tokens=True, return_length=True)["length"]
            if any(size > encoder.max_seq_length for size in lengths):
                raise ValueError("Input exceeds official model context limit")
            started = time.monotonic()
            with torch.inference_mode():
                embeddings = encoder.encode(inputs, batch_size=16, normalize_embeddings=True, show_progress_bar=False)
            print(f"Embedding complete inputs={len(inputs)} elapsed_seconds={time.monotonic() - started:.3f}", flush=True)
            self.respond(200, {"model": TAG, "embeddings": embeddings.tolist()})
        except (ValueError, TypeError, KeyError) as error:
            self.respond(400, {"error": str(error)})
        except Exception as error:
            self.respond(500, {"error": "Embedding execution failed"})
        finally:
            if locked:
                INFERENCE_LOCK.release()
            if admitted:
                ADMISSION.release()


if __name__ == "__main__":
    load_model()
    print(f"Ready: {MODEL_ID}@{REVISION}; {RUNTIME}; dtype={next(model.parameters()).dtype}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", 11434), Handler).serve_forever()
