"""Pinned local cross encoder; called only while holding the server inference lock."""
import math
import os
import time
import torch
import monitoring
from sentence_transformers import CrossEncoder

ENABLED = os.environ.get("RERANKER_ENABLED", "false").lower() == "true"
MODEL_ID = os.environ.get("RERANKER_MODEL", "BAAI/bge-reranker-v2-m3")
REVISION = os.environ.get("RERANKER_REVISION", "953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e")
DEVICE = os.environ.get("RERANKER_DEVICE", os.environ.get("EMBEDDING_DEVICE", "cpu"))
DTYPE = os.environ.get("RERANKER_DTYPE", "float16" if DEVICE == "cuda" else "float32")
BATCH_SIZE = int(os.environ.get("RERANKER_BATCH_SIZE", "2"))
MAX_TOKENS = int(os.environ.get("RERANKER_MAX_TOKENS", "1024"))
monitoring.BATCH_SIZE.labels('rerank').set(BATCH_SIZE)
if DEVICE not in ("cpu", "cuda") or DTYPE not in ("float16", "float32") \
        or (DEVICE == "cpu" and DTYPE != "float32") or not 1 <= BATCH_SIZE <= 16 \
        or not 128 <= MAX_TOKENS <= 8192:
    raise ValueError("Invalid reranker configuration")
model = None


def load_model():
    global model
    if model is None:
        if DEVICE == "cuda" and not torch.cuda.is_available():
            raise RuntimeError("Reranker CUDA unavailable")
        loaded = CrossEncoder(MODEL_ID, revision=REVISION, device=DEVICE, max_length=MAX_TOKENS,
                              trust_remote_code=False, activation_fn=torch.nn.Identity(),
                              model_kwargs={"torch_dtype": getattr(torch, DTYPE)})
        loaded.model.eval()
        model = loaded
        monitoring.MODEL_READY.labels('rerank').set(1)
    return model


def rerank(payload):
    if not ENABLED:
        raise ValueError("Reranker is disabled")
    if payload.get("model") != MODEL_ID or payload.get("revision") != REVISION:
        raise ValueError("Reranker identity mismatch")
    query, documents = payload.get("query"), payload.get("documents")
    if not isinstance(query, str) or not query.strip() or len(query) > 2000 \
            or not isinstance(documents, list) or not 1 <= len(documents) <= 100:
        raise ValueError("Invalid reranker inputs")
    ids, texts = [], []
    for item in documents:
        if not isinstance(item, dict) or not isinstance(item.get("id"), str) \
                or not 1 <= len(item["id"]) <= 128 or item["id"] in ids \
                or not isinstance(item.get("text"), str) or not item["text"].strip() \
                or len(item["text"]) > 16000:
            raise ValueError("Invalid reranker document")
        ids.append(item["id"])
        texts.append(item["text"])
    encoder = load_model()
    lengths = encoder.tokenizer([query] * len(texts), texts, truncation=False, return_length=True)["length"]
    if any(length > MAX_TOKENS for length in lengths):
        raise ValueError("Reranker pair exceeds token limit; truncation is disabled")
    started = time.monotonic()
    with monitoring.INFERENCE_SECONDS.labels('rerank').time(), torch.inference_mode():
        scores = [float(score) for score in encoder.predict(list(zip([query] * len(texts), texts)),
                  batch_size=BATCH_SIZE, show_progress_bar=False)]
    if len(scores) != len(ids) or not all(math.isfinite(score) for score in scores):
        raise RuntimeError("Invalid reranker scores")
    monitoring.ITEMS.labels('rerank').inc(len(ids))
    print(f"Rerank complete candidates={len(ids)} elapsed_seconds={time.monotonic()-started:.3f}", flush=True)
    return {"model": MODEL_ID, "revision": REVISION,
            "results": [{"id": key, "score": score} for key, score in zip(ids, scores)]}
