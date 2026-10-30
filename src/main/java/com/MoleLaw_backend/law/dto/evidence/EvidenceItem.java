package com.MoleLaw_backend.law.dto.evidence;

/**
 * Immutable evidence captured before the initial answer is generated.
 * LAW_QDRANT sourceId is the retrieval unit ID (MST:articlePath), not a SQL chunk ID.
 * The collected source UUID and indexing identity belong to provenance.
 */
public record EvidenceItem(String id, String kind, String sourceId, String title,
                           String sourceUrl, String content, EvidenceProvenance provenance) {
    public EvidenceItem(String id, String kind, String sourceId, String title,
                        String sourceUrl, String content) {
        this(id, kind, sourceId, title, sourceUrl, content, null);
    }
}
