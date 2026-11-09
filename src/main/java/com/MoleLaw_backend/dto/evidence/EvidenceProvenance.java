package com.MoleLaw_backend.dto.evidence;

/** Values observed by the server when collecting/indexing; unknown values remain null. */
public record EvidenceProvenance(String sourceUuid, String mst, String lawId, String effectiveDate,
                                 String articlePath, String modelRevision,
                                 String configFingerprint, String generation) {
}
