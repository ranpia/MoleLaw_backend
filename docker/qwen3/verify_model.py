"""Compare the adapter with an independent official SentenceTransformer load."""
import json
import math
import subprocess
import sys
import urllib.request

REVISION = "97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3"
TAG = "qwen3-embedding-0.6b:official"
TEXTS = ["임대차 계약을 해지하려면 어떤 조건이 필요한가요?", "민법 제750조\n고의 또는 과실로 인한 위법행위로 타인에게 손해를 가한 자는 그 손해를 배상할 책임이 있다.", "근로자가 퇴직금을 받을 수 있는 요건", "한국어 검색 임베딩 검증"]


def request(path, payload=None):
    encoded = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request("http://127.0.0.1:11434" + path, data=encoded, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as response:
        return json.load(response)


if __name__ == "__main__":
    if request("/api/tags")["models"][0]["digest"] != REVISION:
        raise RuntimeError("Official revision mismatch")
    request("/api/embed", {"model": TAG, "input": [], "keep_alive": 0})
    # Child exits before API inference, avoiding two resident model copies.
    source = '''
import json, sys, torch
from sentence_transformers import SentenceTransformer
torch.set_num_threads(6)
torch.set_num_interop_threads(1)
payload = json.loads(sys.stdin.read())
model = SentenceTransformer("Qwen/Qwen3-Embedding-0.6B", revision=payload["revision"], device="cpu", trust_remote_code=False, local_files_only=True)
model.float().eval()
with torch.inference_mode():
    vectors = model.encode(payload["texts"], batch_size=16, show_progress_bar=False, normalize_embeddings=True)
print(json.dumps(vectors.tolist()))
'''
    child = subprocess.run([sys.executable, "-c", source], input=json.dumps({"revision": REVISION, "texts": TEXTS}), text=True, capture_output=True, check=True)
    reference = json.loads(child.stdout)
    actual = request("/api/embed", {"model": TAG, "input": TEXTS, "truncate": False, "options": {"num_thread": 6, "num_gpu": 0}})["embeddings"]
    if len(actual) != len(TEXTS) or len(reference) != len(TEXTS):
        raise RuntimeError("Embedding batch size mismatch")
    difference = 0.0
    for left, right in zip(actual, reference):
        if len(left) != 1024 or len(right) != 1024 or any(not math.isfinite(v) for v in left + right):
            raise RuntimeError("Invalid official vector dimensions/values")
        if abs(sum(v * v for v in left) - 1.0) > 0.00001:
            raise RuntimeError("Adapter vector is not normalized")
        difference = max(difference, max(abs(a - b) for a, b in zip(left, right)))
    if difference > 0.000001:
        raise RuntimeError(f"Official reference differs: {difference}")
    print(json.dumps({"result": "PASS", "revision": REVISION, "texts": len(TEXTS), "dimensions": 1024, "maximumAbsoluteDifference": difference}))
