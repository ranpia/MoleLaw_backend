-- Development replacement: legacy vectors are no longer read by the application.
ALTER TABLE chat_room
    MODIFY COLUMN title VARCHAR(255) NOT NULL,
    MODIFY COLUMN user_id BIGINT NOT NULL,
    MODIFY COLUMN created_at DATETIME(6) NOT NULL,
    ADD CONSTRAINT ck_chat_room_title CHECK (CHAR_LENGTH(TRIM(title)) > 0),
    DROP FOREIGN KEY fk_chat_room_user,
    ADD CONSTRAINT fk_chat_room_user_cascade FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE;

-- INFO is synthesized from the immutable snapshot; pre-existing INFO data needs explicit handling.
ALTER TABLE message
    MODIFY COLUMN sender VARCHAR(16) NOT NULL,
    MODIFY COLUMN content LONGTEXT NOT NULL,
    MODIFY COLUMN timestamp DATETIME(6) NOT NULL,
    ADD CONSTRAINT ck_message_content CHECK (CHAR_LENGTH(TRIM(content)) > 0),
    ADD CONSTRAINT ck_message_sender CHECK (sender IN ('USER', 'BOT')),
    DROP FOREIGN KEY fk_message_room,
    ADD CONSTRAINT fk_message_room_cascade FOREIGN KEY (chat_room_id) REFERENCES chat_room (id) ON DELETE CASCADE;

ALTER TABLE chat_evidence_snapshot
    ADD COLUMN schema_version INT NOT NULL DEFAULT 1;

ALTER TABLE legal_source
    MODIFY COLUMN effective_date VARCHAR(8),
    ADD INDEX idx_legal_source_history (source_type, external_id, collected_at, id),
    ADD CONSTRAINT ck_legal_source_type CHECK (source_type IN ('LAW', 'CASE')),
    ADD CONSTRAINT ck_legal_source_external_id CHECK (external_id REGEXP '^[0-9]{1,20}$'),
    ADD CONSTRAINT ck_legal_source_law_id CHECK (
        (source_type = 'LAW' AND law_id IS NOT NULL AND law_id REGEXP '^[0-9]{1,20}$')
        OR (source_type = 'CASE' AND law_id IS NULL));

CREATE TABLE legal_source_index (
    generation VARCHAR(36) NOT NULL,
    source_id VARCHAR(36) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    config_fingerprint VARCHAR(64) NOT NULL,
    collection_name VARCHAR(128) NOT NULL,
    model_name VARCHAR(255) NOT NULL,
    model_revision VARCHAR(255) NOT NULL,
    dimensions INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6),
    chunk_count INT NOT NULL,
    PRIMARY KEY (generation),
    INDEX idx_source_index_lookup (config_fingerprint, status, completed_at),
    CONSTRAINT fk_source_index_source FOREIGN KEY (source_id) REFERENCES legal_source (id),
    CONSTRAINT ck_source_index_status CHECK (status IN ('WRITING', 'COMPLETE', 'FAILED')),
    CONSTRAINT ck_source_index_dimensions CHECK (dimensions > 0),
    CONSTRAINT ck_source_index_chunk_count CHECK (chunk_count >= 0),
    CONSTRAINT ck_source_index_complete CHECK (status <> 'COMPLETE' OR (completed_at IS NOT NULL AND chunk_count > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DROP TABLE law_embedding;
DROP TABLE law_chunk;
DROP TABLE law;
