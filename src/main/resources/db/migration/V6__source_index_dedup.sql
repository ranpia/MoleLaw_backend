-- Existing generations remain untouched. New WRITING/COMPLETE work claims its content/configuration key.
ALTER TABLE legal_source_index
    ADD COLUMN dedup_key VARCHAR(64) NULL,
    ADD CONSTRAINT uk_source_index_dedup UNIQUE (dedup_key);
