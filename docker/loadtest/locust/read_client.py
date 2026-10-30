import json
import os
from pathlib import Path
import random
from locust import HttpUser, between, task
from locust.exception import StopUser


class ReadOnlyUser(HttpUser):
    abstract = True
    host = "http://gateway:8080"
    wait_time = between(2, 5)
    active_environment = None

    def select_account(self, fixture):
        return fixture["users"][0]

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
        type(self).active_environment = self.environment
        self.logged_in = False
        self.show_rooms = True
        fixture = json.loads(Path("/fixtures/manifest.json").read_text(encoding="utf-8"))
        if fixture["dataset"] != "lt-read-v1" or len(fixture["users"]) != 20:
            raise ValueError("Prepare the reviewed synthetic fixture first")
        self.account = self.select_account(fixture)
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
                    response.failure(f"Room list mismatch/status={response.status_code}/transport={type(response.error).__name__}")
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
                    response.failure(f"Message mismatch/status={response.status_code}/transport={type(response.error).__name__}")
        self.show_rooms = not self.show_rooms

    def on_stop(self):
        if self.logged_in:
            csrf = self.request_json("GET", "/api/auth/csrf", "cleanup: csrf")
            if csrf and csrf.get("token"):
                self.request_json("POST", "/api/auth/logout", "cleanup: logout", csrf=csrf["token"])
