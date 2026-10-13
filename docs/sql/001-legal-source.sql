-- Manual MySQL migration. This project does not run Flyway or execute this file automatically.
-- Apply to the target database before deploying legal API tools when schema auto-update is disabled.
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
