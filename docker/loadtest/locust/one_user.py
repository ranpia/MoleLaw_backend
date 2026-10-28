"""One local user, 60 seconds, read-only chat browsing. No AI endpoint is called."""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import random
import re
import time

import gevent
from locust import events
from locust.exception import StopUser

HOST = "http://gateway:8080"


@events.test_start.add_listener
def record_start(environment, **kwargs):
    options = environment.parsed_options
    if (options.num_users != 1 or options.run_time != 60 or environment.host != HOST
            or options.master or options.worker):
        raise SystemExit("This first exercise requires one local user, 60 seconds, and the test gateway")
    environment.exercise_start = datetime.now(timezone.utc).isoformat()
    environment.exercise_clock = time.monotonic()
    print("Starting one-user read exercise: 60 seconds, wait 2-5 seconds between reads.", flush=True)


@events.request.add_listener
def stop_on_error(exception, **kwargs):
    if exception is not None:
        # No exception body is printed: it may contain response context.
        env = OneUserReads.active_environment
        if env is not None:
            env.process_exit_code = 1
            gevent.spawn(env.runner.quit)


@events.quitting.add_listener
def save_summary(environment, **kwargs):
    elapsed = time.monotonic() - getattr(environment, "exercise_clock", time.monotonic())
    rows = []
    for entry in environment.stats.entries.values():
        rows.append({"name": entry.name, "method": entry.method, "requests": entry.num_requests,
                     "failures": entry.num_failures, "averageMs": entry.avg_response_time,
                     "p95Ms": entry.get_response_time_percentile(0.95),
                     "p99Ms": entry.get_response_time_percentile(0.99), "maxMs": entry.max_response_time})
    read_rows = [row for row in rows if row["name"].startswith("read:")]
    reads = sum(row["requests"] for row in read_rows)
    failures = environment.stats.total.num_failures
    if failures or not reads or elapsed < 55:
        environment.process_exit_code = 1
    summary = {"startedAtUtc": getattr(environment, "exercise_start", None),
               "finishedAtUtc": datetime.now(timezone.utc).isoformat(),
               "elapsedSecondsIncludingSetupAndCleanup": round(elapsed, 3), "users": 1,
               "host": HOST, "dataset": "lt-read-v1", "waitSeconds": [2, 5],
               "readRequests": reads, "allRequests": environment.stats.total.num_requests,
               "allFailures": failures, "readRpsOverRun": reads / elapsed if elapsed > 0 else 0,
               "passed": not failures and reads > 0 and elapsed >= 55, "requests": rows,
               "limitations": "One user, short warm-cache exercise; no capacity claim or latency SLO. Percentiles use few rounded samples."}
    run_id = os.environ["LT_RUN_ID"]
    if not re.fullmatch(r"one-user-\d{8}T\d{6}Z", run_id):
        raise ValueError("Invalid run ID")
    output = Path("/results") / run_id / "summary.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(f"Read exercise complete: requests={reads}, failures={failures}, summary={output}", flush=True)


from read_client import ReadOnlyUser


class OneUserReads(ReadOnlyUser):
    abstract = False
