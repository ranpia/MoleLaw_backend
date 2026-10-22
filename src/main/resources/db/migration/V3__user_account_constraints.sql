-- Existing development data must be reset explicitly before applying this migration.
ALTER TABLE `user`
    RENAME COLUMN password TO password_hash,
    MODIFY COLUMN email VARCHAR(254) NOT NULL,
    MODIFY COLUMN nickname VARCHAR(50) NOT NULL,
    MODIFY COLUMN provider VARCHAR(16) NOT NULL,
    ADD COLUMN provider_subject VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD CONSTRAINT uk_user_provider_subject UNIQUE (provider, provider_subject),
    ADD CONSTRAINT ck_user_email_nonblank CHECK (CHAR_LENGTH(TRIM(email)) > 0),
    ADD CONSTRAINT ck_user_nickname_nonblank CHECK (CHAR_LENGTH(TRIM(nickname)) > 0),
    ADD CONSTRAINT ck_user_provider CHECK (BINARY provider IN ('local', 'google', 'kakao')),
    ADD CONSTRAINT ck_user_credentials CHECK (
        (BINARY provider = 'local' AND password_hash IS NOT NULL
            AND CHAR_LENGTH(TRIM(password_hash)) > 0 AND provider_subject IS NULL)
        OR
        (BINARY provider IN ('google', 'kakao') AND password_hash IS NULL
            AND provider_subject IS NOT NULL AND CHAR_LENGTH(TRIM(provider_subject)) > 0)
    );
