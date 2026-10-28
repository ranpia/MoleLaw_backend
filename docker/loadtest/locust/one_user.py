"""One local user, 60 seconds, read-only chat browsing. No AI endpoint is called."""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import random
import re
import time

import gevent
from locust import HttpUser, between, events, task
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


class OneUserReads(HttpUser):
    host = HOST
    wait_time = between(2, 5)
    active_environment = None

    def request_json(self, method, path, name, payload=None, csrf=None):
        with self.client.request(method, path, name=name, json=payload,
                                 headers={"X-XSRF-TOKEN": csrf} if csrf else {},
                                 timeout=(3, 10), catch_response=True) as response:
            if response.status_code != 200:
                response.failure(f"Unexpected HTTP status {response.status_code}")
                return None
            try:
                return response.json()
            except ValueError:
                response.failure("Expected JSON response")
                return None

    def on_start(self):
        OneUserReads.active_environment = self.environment
        self.logged_in = False
        self.show_rooms = True
        fixture = json.loads(Path("/fixtures/manifest.json").read_text(encoding="utf-8"))
        if fixture["dataset"] != "lt-read-v1" or len(fixture["users"]) != 20:
            raise ValueError("Prepare the reviewed synthetic fixture first")
        self.account = fixture["users"][0]
        self.fixture = fixture
        password = os.environ.get("LT_DATA_PASSWORD", "")
        if not password:
            raise ValueError("Fixture password is missing")
        csrf = self.request_json("GET", "/api/auth/csrf", "setup: csrf")
        if not csrf or not csrf.get("token"):
            raise StopUser()
        result = self.request_json("POST", "/api/auth/login", "setup: login",
                                   {"email": self.account["email"], "password": password}, csrf["token"])
        if not result or not result.get("accessToken"):
            raise StopUser()
        self.logged_in = True

    @task
    def browse(self):
        if self.show_rooms:
            with self.client.get("/api/chat-rooms?page=0&size=20", name="read: room list", timeout=(3, 10), catch_response=True) as response:
                try:
                    rooms = response.json()
                    valid = (response.status_code == 200 and isinstance(rooms, list)
                             and {room["id"] for room in rooms} == set(self.account["roomIds"])
                             and len(rooms) == self.fixture["roomsPerUser"]
                             and all(room["preview"] and not room["preview"].startswith("gcm1.") for room in rooms))
                except (ValueError, KeyError, TypeError):
                    valid = False
                if not valid:
                    response.failure("Room list content or ownership mismatch")
        else:
            index = random.randrange(len(self.account["roomIds"]))
            room_id = self.account["roomIds"][index]
            with self.client.get(f"/api/chat-rooms/{room_id}/messages", name="read: messages", timeout=(3, 10), catch_response=True) as response:
                try:
                    messages = response.json()
                    stored = [message for message in messages if message["sender"] != "INFO"]
                    valid = (response.status_code == 200 and len(stored) == self.fixture["messagesPerRoom"]
                             and sum(message["sender"] == "INFO" for message in messages) == 1
                             and all(message["sender"] == ("USER" if i % 2 == 0 else "BOT")
                                     and self.account["roomMarkers"][index] in message["content"]
                                     for i, message in enumerate(stored)))
                except (ValueError, KeyError, TypeError):
                    valid = False
                if not valid:
                    response.failure("Message decryption, order or evidence mismatch")
        self.show_rooms = not self.show_rooms

    def on_stop(self):
        if self.logged_in:
            csrf = self.request_json("GET", "/api/auth/csrf", "cleanup: csrf")
            if csrf and csrf.get("token"):
                self.request_json("POST", "/api/auth/logout", "cleanup: logout", csrf=csrf["token"])
