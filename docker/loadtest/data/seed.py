"""Offline synthetic read fixtures; never run against development/production databases."""
import base64
import hashlib
import json
import os
from pathlib import Path
import sys
from datetime import datetime, timedelta
import uuid

import bcrypt
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
import pymysql

DATASET = "lt-read-v1"
USERS, ROOMS, MESSAGES = 20, 10, 20
DATABASE = "molelaw_loadtest"
KEY_VERSION = "loadtest-v1"
AAD = f"gcm1.{KEY_VERSION}".encode()


def message_text(user, room, index, evidence_id):
    role = "질문" if index % 2 == 0 else "답변"
    repeat = (1, 4, 12)[(room - 1) % 3]
    body = "조회와 암호화 복원 성능을 확인하는 가상 상담이며 실제 법률 자문이 아닙니다. " * repeat
    citation = f" [[{evidence_id}]]" if index % 2 else ""
    return f"[합성 데이터] 사용자 {user:04d} 방 {room:02d} {role} {index + 1:02d}. {body}{citation}"


def envelope(cipher, plaintext):
    nonce = os.urandom(12)
    encrypted = cipher.encrypt(nonce, plaintext.encode(), AAD)
    return f"gcm1.{KEY_VERSION}.{base64.b64encode(nonce).decode()}.{base64.b64encode(encrypted).decode()}"


def decrypt(cipher, value):
    fmt, version, nonce, encrypted = value.split(".")
    if (fmt, version) != ("gcm1", KEY_VERSION):
        raise RuntimeError("Existing fixture has an incompatible encryption envelope")
    return cipher.decrypt(base64.b64decode(nonce), base64.b64decode(encrypted), AAD).decode()


def main():
    if os.environ.get("LT_SEED_CONFIRM") != DATABASE:
        raise RuntimeError("Explicit isolated database confirmation is required")
    password = os.environ["LT_DATA_PASSWORD"].encode()
    if not 32 <= len(password) <= 72:
        raise RuntimeError("Fixture password must contain 32-72 UTF-8 bytes")
    key = base64.b64decode(os.environ["LT_CHAT_ENCRYPTION_KEY"], validate=True)
    if len(key) != 32:
        raise RuntimeError("Expected a 32-byte chat encryption key")
    cipher = AESGCM(key)
    manifest = {"dataset": DATASET, "roomsPerUser": ROOMS, "messagesPerRoom": MESSAGES, "users": []}
    created = {"users": 0, "rooms": 0, "messages": 0, "snapshots": 0}
    connection = pymysql.connect(
        host="mysql", port=3306, user="molelaw_loadtest", database=DATABASE,
        password=os.environ["LT_MYSQL_PASSWORD"], charset="utf8mb4",
        autocommit=False, cursorclass=pymysql.cursors.DictCursor,
        connect_timeout=5, read_timeout=30, write_timeout=30,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT DATABASE() AS db, GET_LOCK(%s, 10) AS acquired", (DATASET,))
            guard = cursor.fetchone()
            if guard["db"] != DATABASE or guard["acquired"] != 1:
                raise RuntimeError("Isolated database or dataset lock check failed")
            cursor.execute("SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")
            migration = cursor.fetchone()
            if migration != {"version": "8", "success": 1}:
                raise RuntimeError("Fixture schema must be reviewed against the current Flyway V8")
            cursor.execute("SELECT email FROM `user` WHERE email LIKE %s", (f"{DATASET}-u%@example.invalid",))
            expected_emails = {f"{DATASET}-u{u:04d}@example.invalid" for u in range(1, USERS + 1)}
            if any(row["email"] not in expected_emails for row in cursor.fetchall()):
                raise RuntimeError("Existing dataset has a different size; do not overwrite it")

            for user_number in range(1, USERS + 1):
                email = f"{DATASET}-u{user_number:04d}@example.invalid"
                nickname = f"부하테스트{user_number:04d}"
                cursor.execute("SELECT id, nickname, password_hash, provider, provider_subject FROM `user` WHERE email=%s", (email,))
                matches = cursor.fetchall()
                if not matches:
                    hashed = bcrypt.hashpw(password, bcrypt.gensalt(rounds=10, prefix=b"2a")).decode()
                    cursor.execute("INSERT INTO `user` (email,password_hash,nickname,provider,provider_subject) VALUES (%s,%s,%s,'local',NULL)",
                                   (email, hashed, nickname))
                    user_id = cursor.lastrowid
                    created["users"] += 1
                else:
                    if len(matches) != 1:
                        raise RuntimeError("Conflicting fixture account")
                    account = matches[0]
                    if (account["provider"] != "local" or account["provider_subject"] is not None
                            or account["nickname"] != nickname
                            or not bcrypt.checkpw(password, account["password_hash"].encode())):
                        raise RuntimeError("Existing fixture account differs; no passwords are reset")
                    user_id = account["id"]
                cursor.execute("SELECT id,title FROM chat_room WHERE user_id=%s ORDER BY id", (user_id,))
                existing_rows = cursor.fetchall()
                existing_rooms = {row["title"]: row["id"] for row in existing_rows}
                if len(existing_rows) != len(existing_rooms):
                    raise RuntimeError("Duplicate fixture room titles are not supported")
                expected_titles = {f"[합성-{DATASET}] 사용자{user_number:04d} 방{r:02d}" for r in range(1, ROOMS + 1)}
                if set(existing_rooms) - expected_titles:
                    raise RuntimeError("Fixture account contains additional rooms; use a fresh dataset")
                room_ids = []
                for room_number in range(1, ROOMS + 1):
                    title = f"[합성-{DATASET}] 사용자{user_number:04d} 방{room_number:02d}"
                    moment = datetime(2026, 1, 1) + timedelta(minutes=user_number * 100 + room_number)
                    evidence_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"{DATASET}/{user_number}/{room_number}"))
                    evidence = [{"id": evidence_id, "kind": "LAW_CHUNK", "sourceId": f"{DATASET}-{user_number}-{room_number}",
                                 "title": "조회 시험용 합성 근거 (실제 법령 아님)",
                                 "sourceUrl": "https://example.invalid/loadtest/synthetic-evidence",
                                 "content": "법률 효력이 없는 합성 근거입니다. RAG 품질 평가에 사용하지 않습니다.", "provenance": None}]
                    evidence_json = json.dumps(evidence, ensure_ascii=False, separators=(",", ":"))
                    evidence_hash = hashlib.sha256(evidence_json.encode()).hexdigest()
                    is_new = title not in existing_rooms
                    if is_new:
                        cursor.execute("INSERT INTO chat_room (title,user_id,created_at) VALUES (%s,%s,%s)", (title, user_id, moment))
                        room_id = cursor.lastrowid
                        cursor.execute("INSERT INTO chat_evidence_snapshot (room_id,evidence_json,content_hash,captured_at,schema_version) VALUES (%s,%s,%s,%s,2)",
                                       (room_id, evidence_json, evidence_hash, moment))
                        values = [(room_id, "USER" if i % 2 == 0 else "BOT", envelope(cipher, message_text(user_number, room_number, i, evidence_id)),
                                   moment + timedelta(seconds=i), False, False) for i in range(MESSAGES)]
                        cursor.executemany("INSERT INTO message (chat_room_id,sender,content,timestamp,case_search_requested,case_search_consent) VALUES (%s,%s,%s,%s,%s,%s)", values)
                        created["rooms"] += 1
                        created["messages"] += MESSAGES
                        created["snapshots"] += 1
                    else:
                        room_id = existing_rooms[title]
                        cursor.execute("SELECT evidence_json,content_hash,schema_version FROM chat_evidence_snapshot WHERE room_id=%s", (room_id,))
                        snapshot = cursor.fetchone()
                        if snapshot != {"evidence_json": evidence_json, "content_hash": evidence_hash, "schema_version": 2}:
                            raise RuntimeError("Existing synthetic snapshot differs; no data is overwritten")
                        cursor.execute("SELECT sender,content,case_search_requested,case_search_consent FROM message WHERE chat_room_id=%s ORDER BY timestamp,id", (room_id,))
                        messages = cursor.fetchall()
                        if len(messages) != MESSAGES:
                            raise RuntimeError("Existing fixture message count differs")
                        for i, message in enumerate(messages):
                            if (message["sender"] != ("USER" if i % 2 == 0 else "BOT")
                                    or message["case_search_requested"] or message["case_search_consent"]
                                    or decrypt(cipher, message["content"]) != message_text(user_number, room_number, i, evidence_id)):
                                raise RuntimeError("Existing fixture message differs")
                    room_ids.append(room_id)
                manifest["users"].append({"userId": user_id, "email": email, "roomIds": room_ids,
                                          "roomMarkers": [f"사용자 {user_number:04d} 방 {r:02d}" for r in range(1, ROOMS + 1)]})
        connection.commit()
    except BaseException:
        connection.rollback()
        raise
    finally:
        connection.close()  # Releases the advisory lock as well.

    output = Path("/results/manifest.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(".tmp")
    temporary.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(output)
    print(json.dumps({"dataset": DATASET, "created": created,
                      "total": {"users": USERS, "rooms": USERS * ROOMS, "messages": USERS * ROOMS * MESSAGES,
                                "snapshots": USERS * ROOMS}, "manifest": "build/loadtest-data/manifest.json"}))


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as error:
        print(f"Fixture preparation stopped: {error}", file=sys.stderr)
        sys.exit(1)
    except Exception as error:
        # DB/crypto exceptions may carry sensitive context. Do not dump SQL, keys or tracebacks.
        print(f"Fixture preparation failed ({type(error).__name__}); no credentials are printed.", file=sys.stderr)
        sys.exit(1)
