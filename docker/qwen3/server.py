"""Local evaluation adapter for one immutable official Qwen3 checkpoint.

Implements only the embedding evaluator's local request contract, not Ollama.
Single HTTPServer serializes requests. Model files are stored in a Docker volume.
"""
import gc
import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from importlib.metadata import version

import torch
from sentence_transformers import SentenceTransformer

MODEL_ID = "Qwen/Qwen3-Embedding-0.6B"
REVISION = "97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3"
TAG = "qwen3-embedding-0.6b:official"
THREADS = 6
torch.set_num_threads(THREADS)
torch.set_num_interop_threads(1)
RUNTIME = ";".join(f"{name}/{version(name)}" for name in ("sentence-transformers", "torch", "transformers"))
model = None


def load_model():
    global model
    if model is None:
        model = SentenceTransformer(MODEL_ID, revision=REVISION, device="cpu", trust_remote_code=False)
        model.float()
        model.eval()
        if model.get_embedding_dimension() != 1024 or model.max_seq_length != 32768:
            raise RuntimeError("Unexpected official model dimensions/context limit")
        if model[1].get_config_dict().get("pooling_mode") != "lasttoken":
            raise RuntimeError("Unexpected official last-token pooling configuration")

    return model


class Handler(BaseHTTPRequestHandler):
    def respond(self, status, payload):
        encoded = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

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
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 1 or length > 1_048_576:
                raise ValueError("Invalid request size")
            payload = json.loads(self.rfile.read(length))
            if self.path != "/api/embed":
                self.respond(404, {"error": "Unknown evaluation route"})
                return
            if payload.get("model") != TAG:
                raise ValueError("Only the pinned official Qwen3-Embedding-0.6B model is supported")
            inputs = payload.get("input")
            if not isinstance(inputs, list) or len(inputs) > 32 or any(not isinstance(text, str) for text in inputs):
                raise ValueError("Expected at most 32 text inputs")
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
            with torch.inference_mode():
                embeddings = encoder.encode(inputs, batch_size=16, normalize_embeddings=True, show_progress_bar=False)
            self.respond(200, {"model": TAG, "embeddings": embeddings.tolist()})
        except (ValueError, TypeError, KeyError) as error:
            self.respond(400, {"error": str(error)})
        except Exception as error:
            self.respond(500, {"error": str(error)})


if __name__ == "__main__":
    load_model()
    print(f"Ready: {MODEL_ID}@{REVISION}; {RUNTIME}; dtype={next(model.parameters()).dtype}", flush=True)
    HTTPServer(("0.0.0.0", 11434), Handler).serve_forever()
