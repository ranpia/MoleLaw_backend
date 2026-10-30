"""Bounded labels only: never record queries, document IDs, tokens or text."""
from prometheus_client import Counter, Gauge, Histogram, start_http_server

BUCKETS = (.005, .01, .025, .05, .1, .25, .5, 1, 2, 5, 10, 30, 60, 120, 300, 900)
REQUESTS = Counter('kure_requests_total', 'Completed inference HTTP requests', ['operation', 'status'])
REQUEST_SECONDS = Histogram('kure_request_duration_seconds', 'Full POST handler duration', ['operation'], buckets=BUCKETS)
WAIT_SECONDS = Histogram('kure_queue_wait_seconds', 'Time acquiring shared inference gate', ['operation'], buckets=BUCKETS)
INFERENCE_SECONDS = Histogram('kure_inference_duration_seconds', 'Encoder/predict execution including failed attempts', ['operation'], buckets=BUCKETS)
WAITING = Gauge('kure_queue_waiting', 'Requests waiting for the shared inference gate', ['operation'])
ACTIVE = Gauge('kure_inference_active', 'Requests holding the shared inference gate', ['operation'])
REJECTED = Counter('kure_rejections_total', 'Admission or wait rejection', ['operation', 'reason'])
ITEMS = Counter('kure_processed_items_total', 'Successfully encoded texts or scored candidates', ['operation'])
MODEL_READY = Gauge('kure_model_ready', 'Model loaded in memory', ['operation'])
QUEUE_CAPACITY = Gauge('kure_queue_capacity', 'Configured shared waiting queue capacity')
BATCH_SIZE = Gauge('kure_batch_size', 'Configured batch size', ['operation'])

for operation in ('embed', 'rerank'):
    WAITING.labels(operation).set(0)
    ACTIVE.labels(operation).set(0)
    MODEL_READY.labels(operation).set(0)
    ITEMS.labels(operation).inc(0)
    REQUEST_SECONDS.labels(operation)
    WAIT_SECONDS.labels(operation)
    INFERENCE_SECONDS.labels(operation)
    for status in ('200', '400', '503', '500'):
        REQUESTS.labels(operation, status).inc(0)
    for reason in ('full', 'timeout'):
        REJECTED.labels(operation, reason).inc(0)


def start(port):
    # Dedicated port: scrape is independent of inference admission and model loading.
    return start_http_server(port, addr='0.0.0.0')
