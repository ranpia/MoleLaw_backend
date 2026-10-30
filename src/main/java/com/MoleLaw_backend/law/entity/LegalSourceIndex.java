package com.MoleLaw_backend.law.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

/** One immutable source/configuration indexing attempt; only COMPLETE attempts are searchable. */
@Entity
@Table(name = "legal_source_index", indexes = @Index(name = "idx_source_index_lookup", columnList = "config_fingerprint,status,completed_at"))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class LegalSourceIndex {
    @Id @Column(length = 36, updatable = false) private String generation;
    @Column(nullable = false, length = 36, updatable = false) private String sourceId;
    @Column(nullable = false, length = 64, updatable = false) private String contentHash;
    @Column(nullable = false, length = 64, updatable = false) private String configFingerprint;
    @Column(nullable = false, length = 128, updatable = false) private String collectionName;
    @Column(nullable = false, length = 255, updatable = false) private String modelName;
    @Column(nullable = false, length = 255, updatable = false) private String modelRevision;
    @Column(nullable = false, updatable = false) private int dimensions;
    @Column(nullable = false, length = 16) private String status;
    @Column(nullable = false, updatable = false) private Instant startedAt;
    private Instant completedAt;
    @Column(nullable = false) private int chunkCount;
    @Column(length = 64, unique = true) private String dedupKey;

    public LegalSourceIndex(String sourceId, String contentHash, String fingerprint, String collection,
                            String model, String revision, int dimensions) {
        generation = UUID.randomUUID().toString();
        this.sourceId = sourceId; this.contentHash = contentHash; configFingerprint = fingerprint;
        collectionName = collection; modelName = model; modelRevision = revision; this.dimensions = dimensions;
        status = "WRITING"; startedAt = Instant.now();
    }
    public void complete(int count) { chunkCount = count; status = "COMPLETE"; completedAt = Instant.now(); }
    public void claim(String key) {
        if (!"WRITING".equals(status) || dedupKey != null || key == null || !key.matches("[a-f0-9]{64}"))
            throw new IllegalStateException("Invalid index claim");
        dedupKey = key;
    }
    public void fail() { status = "FAILED"; dedupKey = null; }
}
