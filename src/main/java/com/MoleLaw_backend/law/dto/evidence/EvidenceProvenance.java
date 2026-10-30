package com.MoleLaw_backend.law.dto.evidence;

/** Values observed by the server when collecting/indexing; unknown values remain null. */
public record EvidenceProvenance(String sourceUuid, String mst, String lawId, String effectiveDate,
                                 String articlePath, String modelRevision,
                                 String configFingerprint, String generation) {
}
