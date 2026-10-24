-- No law text or vectors: only relational membership and embedding-input identity.
CREATE TABLE law_chunk_index (
    point_id VARCHAR(36) NOT NULL,
    generation VARCHAR(36) NOT NULL,
    article_path VARCHAR(64) NOT NULL,
    chunk_index INT NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    PRIMARY KEY (point_id),
    INDEX idx_chunk_generation (generation),
    CONSTRAINT fk_chunk_index_generation FOREIGN KEY (generation) REFERENCES legal_source_index (generation),
    CONSTRAINT uk_chunk_generation_position UNIQUE (generation, article_path, chunk_index),
    CONSTRAINT ck_chunk_index_position CHECK (chunk_index >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
