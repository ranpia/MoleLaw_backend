"""Real configured-device embedding smoke test with concurrent health checks."""
import concurrent.futures
import json
import math
import os
import time
import urllib.request

base = "http://127.0.0.1:11434"
threads = int(os.environ.get("EMBEDDING_THREADS", "6"))
gpu_count = 1 if os.environ.get("EMBEDDING_DEVICE", "cpu") == "cuda" else 0


def embed():
    payload = {"model": "kure-v1:official", "input": ["건축물의 높이 제한과 공항 주변 규제에 관한 검토. " * 20] * 16,
               "truncate": False, "options": {"num_thread": threads, "num_gpu": gpu_count}}
    request = urllib.request.Request(base + "/api/embed", data=json.dumps(payload).encode(),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.load(response)["embeddings"]


started = time.monotonic()
latencies = []
with concurrent.futures.ThreadPoolExecutor() as executor:
    pending = executor.submit(embed)
    while True:
        probe = time.monotonic()
        with urllib.request.urlopen(base + "/api/version", timeout=2) as health:
            assert health.status == 200
        latencies.append(time.monotonic() - probe)
        if pending.done():
            break
        time.sleep(0.2)
    vectors = pending.result()
assert len(vectors) == 16 and all(len(vector) == 1024 for vector in vectors)
assert all(all(math.isfinite(v) for v in vector) and abs(math.sqrt(sum(v*v for v in vector)) - 1) < 0.01 for vector in vectors)
print(json.dumps({"vectors": len(vectors), "dimensions": 1024, "health_probes": len(latencies),
                  "max_health_seconds": round(max(latencies), 3),
                  "elapsed_seconds": round(time.monotonic() - started, 2)}))
