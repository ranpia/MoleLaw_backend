-- Historical manual SQL, retained for reference only. DO NOT run on fresh environments.
-- Flyway V1__initial_schema.sql now creates this table automatically with the full schema.
CREATE TABLE legal_source (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    source_type VARCHAR(16) NOT NULL,
    external_id VARCHAR(255) NOT NULL,
    law_id VARCHAR(255) NULL,
    effective_date VARCHAR(255) NULL,
    source_url VARCHAR(2048) NOT NULL,
    collected_at TIMESTAMP(6) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    raw_json LONGTEXT NOT NULL
);
