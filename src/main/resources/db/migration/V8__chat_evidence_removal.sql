CREATE TABLE chat_evidence_removal (
    id VARCHAR(36) NOT NULL,
    room_id BIGINT NOT NULL,
    evidence_id VARCHAR(36) NOT NULL,
    removed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_chat_evidence_removal_room_evidence UNIQUE (room_id, evidence_id),
    CONSTRAINT fk_chat_evidence_removal_room FOREIGN KEY (room_id) REFERENCES chat_room (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
