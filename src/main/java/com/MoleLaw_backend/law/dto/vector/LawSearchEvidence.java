package com.MoleLaw_backend.law.dto.vector;

/** Internal, verified Qdrant retrieval result; not an HTTP response contract. */
public record LawSearchEvidence(String unitId, String referenceId, String externalId, String lawId,
                       String effectiveDate, String title, String text, String articleText,
                       String sourceUrl, double score, String modelRevision,
                       String configFingerprint, String generation) {
    public LawSearchEvidence(String unitId, String referenceId, String externalId, String lawId,
                    String effectiveDate, String title, String text, String articleText, String sourceUrl, double score) {
        this(unitId, referenceId, externalId, lawId, effectiveDate, title, text, articleText, sourceUrl, score, null, null, null);
    }
}
