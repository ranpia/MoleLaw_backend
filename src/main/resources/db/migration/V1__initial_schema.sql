-- Fresh databases only. Keep legacy tables while the current application uses them.
CREATE TABLE `user` (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(255),
    password VARCHAR(255),
    nickname VARCHAR(255),
    provider VARCHAR(255),
    PRIMARY KEY (id),
    CONSTRAINT uk_user_email_provider UNIQUE (email, provider)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE chat_room (
    id BIGINT NOT NULL AUTO_INCREMENT,
    title VARCHAR(255),
    user_id BIGINT,
    created_at DATETIME(6),
    PRIMARY KEY (id),
    INDEX ix_chat_room_user_created (user_id, created_at, id),
    CONSTRAINT fk_chat_room_user FOREIGN KEY (user_id) REFERENCES `user` (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    chat_room_id BIGINT NOT NULL,
    sender ENUM('USER', 'BOT', 'INFO'),
    content TEXT,
    timestamp DATETIME(6),
    PRIMARY KEY (id),
    INDEX ix_message_room_timestamp (chat_room_id, timestamp, id),
    CONSTRAINT fk_message_room FOREIGN KEY (chat_room_id) REFERENCES chat_room (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE law (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    law_code VARCHAR(255),
    department VARCHAR(255),
    created_at DATETIME(6),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE law_chunk (
    id BIGINT NOT NULL AUTO_INCREMENT,
    law_id BIGINT NOT NULL,
    article_number VARCHAR(255),
    clause_number VARCHAR(255),
    chunk_index INT,
    content_text TEXT,
    created_at DATETIME(6),
    PRIMARY KEY (id),
    INDEX ix_law_chunk_law (law_id),
    CONSTRAINT fk_law_chunk_law FOREIGN KEY (law_id) REFERENCES law (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE law_embedding (
    id BIGINT NOT NULL AUTO_INCREMENT,
    law_chunk_id BIGINT NOT NULL,
    embedding_vector MEDIUMBLOB NOT NULL,
    model_name VARCHAR(255),
    created_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_law_embedding_chunk UNIQUE (law_chunk_id),
    CONSTRAINT fk_law_embedding_chunk FOREIGN KEY (law_chunk_id) REFERENCES law_chunk (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE legal_source (
    id VARCHAR(36) NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    external_id VARCHAR(255) NOT NULL,
    law_id VARCHAR(255),
    effective_date VARCHAR(255),
    source_url VARCHAR(2048) NOT NULL,
    collected_at DATETIME(6) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    raw_json LONGTEXT NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
