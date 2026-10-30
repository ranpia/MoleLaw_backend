CREATE TABLE chat_evidence_snapshot (
    room_id BIGINT NOT NULL,
    evidence_json LONGTEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    captured_at DATETIME(6) NOT NULL,
    PRIMARY KEY (room_id),
    CONSTRAINT fk_chat_evidence_snapshot_room FOREIGN KEY (room_id)
        REFERENCES chat_room (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
