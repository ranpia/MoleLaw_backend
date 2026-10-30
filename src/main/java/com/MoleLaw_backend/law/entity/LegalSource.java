package com.MoleLaw_backend.law.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Index;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "legal_source", indexes = @Index(name = "idx_legal_source_history", columnList = "source_type,external_id,collected_at,id"))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class LegalSource {
    @Id
    @Column(length = 36, updatable = false)
    private String id;

    @Column(nullable = false, length = 16, updatable = false)
    private String sourceType;

    @Column(nullable = false, updatable = false)
    private String externalId;

    @Column(updatable = false)
    private String lawId;

    @Column(length = 8, updatable = false)
    private String effectiveDate;

    @Column(nullable = false, length = 2048, updatable = false)
    private String sourceUrl;

    @Column(nullable = false, updatable = false)
    private Instant collectedAt;

    @Column(nullable = false, length = 64, updatable = false)
    private String contentHash;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT", updatable = false)
    private String rawJson;

    public LegalSource(String sourceType, String externalId, String lawId, String effectiveDate,
                       String sourceUrl, String contentHash, String rawJson) {
        this.id = UUID.randomUUID().toString();
        this.sourceType = sourceType;
        this.externalId = externalId;
        this.lawId = lawId;
        this.effectiveDate = effectiveDate;
        this.sourceUrl = sourceUrl;
        this.collectedAt = Instant.now();
        this.contentHash = contentHash;
        this.rawJson = rawJson;
    }
}
