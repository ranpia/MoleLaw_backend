ALTER TABLE message
    ADD COLUMN case_search_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN case_search_consent BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE chat_case_evidence (
    id VARCHAR(36) NOT NULL,
    room_id BIGINT NOT NULL,
    source_id VARCHAR(36) NOT NULL,
    external_id VARCHAR(20) NOT NULL,
    captured_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_chat_case_room_external UNIQUE (room_id, external_id),
    CONSTRAINT fk_chat_case_room FOREIGN KEY (room_id) REFERENCES chat_room (id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_case_source FOREIGN KEY (source_id) REFERENCES legal_source (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
