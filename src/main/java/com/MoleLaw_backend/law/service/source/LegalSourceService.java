package com.MoleLaw_backend.law.service.source;

import com.MoleLaw_backend.law.entity.LegalSource;
import com.MoleLaw_backend.law.repository.LegalSourceRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class LegalSourceService {
    private final LegalSourceRepository repository;
    private final ObjectMapper objectMapper;

    /** Each collection creates a new immutable snapshot, including repeated identifiers. */
    @Transactional
    public LegalSource save(String sourceType, String externalId, String lawId, String effectiveDate,
                            String sourceUrl, JsonNode content) {
        if (!"LAW".equals(sourceType) && !"CASE".equals(sourceType)) {
            throw new IllegalArgumentException("Unsupported legal source type");
        }
        if (externalId == null || !externalId.matches("[0-9]{1,20}") || sourceUrl == null || sourceUrl.length() > 2048
                || content == null || content.isNull()) {
            throw new IllegalArgumentException("Source identifier, URL and content are required");
        }
        java.net.URI uri = java.net.URI.create(sourceUrl);
        if (!"https".equals(uri.getScheme()) || !"www.law.go.kr".equals(uri.getHost())
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Official source URL is required");
        }
        if ("LAW".equals(sourceType) && (lawId == null || !lawId.matches("[0-9]{1,20}"))) {
            throw new IllegalArgumentException("Law LID is required");
        }
        if (effectiveDate != null && effectiveDate.isBlank()) effectiveDate = null;
        if (effectiveDate != null) {
            if (!effectiveDate.matches("[0-9]{8}")) throw new IllegalArgumentException("Invalid effective date");
            try { java.time.LocalDate.parse(effectiveDate, java.time.format.DateTimeFormatter.BASIC_ISO_DATE); }
            catch (java.time.DateTimeException invalid) { throw new IllegalArgumentException("Invalid effective date", invalid); }
        }
        try {
            String rawJson = objectMapper.writeValueAsString(content);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawJson.getBytes(StandardCharsets.UTF_8)));
            return repository.save(new LegalSource(sourceType, externalId, lawId, effectiveDate,
                    sourceUrl, hash, rawJson));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize legal source", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    @Transactional(readOnly = true)
    public LegalSource restore(String referenceId) {
        if (referenceId == null || referenceId.isBlank()) {
            throw new IllegalArgumentException("Source reference is required");
        }
        return repository.findById(referenceId)
                .orElseThrow(() -> new IllegalArgumentException("Legal source reference was not found"));
    }
}
