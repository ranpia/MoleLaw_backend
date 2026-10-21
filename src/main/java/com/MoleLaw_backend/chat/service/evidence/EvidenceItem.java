package com.MoleLaw_backend.chat.service.evidence;

/** Immutable evidence captured before the initial answer is generated. */
public record EvidenceItem(String id, String kind, String sourceId, String title,
                           String sourceUrl, String content) {
}
