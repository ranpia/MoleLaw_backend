package com.MoleLaw_backend.law.service.tool;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.law.dto.evidence.EvidenceProvenance;
import com.MoleLaw_backend.law.service.source.LegalSourceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Restores a server-authorized immutable precedent source, never model-supplied text. */
@Service
@RequiredArgsConstructor
public class CaseEvidenceReader {
    private final LegalSourceService sources;
    private final ObjectMapper mapper;

    public EvidenceItem read(String sourceId, String evidenceId) {
        var source = sources.restore(sourceId);
        try {
            String actualHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(source.getRawJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if (!actualHash.equals(source.getContentHash())) throw new IllegalArgumentException("Precedent source hash mismatch");
            var raw = mapper.readTree(source.getRawJson());
            if (!"CASE".equals(source.getSourceType())
                    || !source.getExternalId().equals(raw.path("판례정보일련번호").asText(raw.path("판례일련번호").asText()))
                    || raw.path("사건명").asText().isBlank() || raw.path("판례내용").asText().isBlank())
                throw new IllegalArgumentException("Invalid immutable precedent source");
            return new EvidenceItem(evidenceId, "CASE_BODY", source.getExternalId(),
                    raw.path("사건명").asText(), source.getSourceUrl(), source.getRawJson(),
                    new EvidenceProvenance(sourceId, null, null, null, null, null, null, null));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid immutable precedent JSON");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
