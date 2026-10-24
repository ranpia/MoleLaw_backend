package com.MoleLaw_backend.law.service.source;

import com.MoleLaw_backend.law.client.vector.LawVectorClient;

import com.MoleLaw_backend.law.entity.LegalSource;
import com.MoleLaw_backend.law.entity.LegalSourceIndex;
import com.MoleLaw_backend.law.repository.LegalSourceIndexRepository;
import com.MoleLaw_backend.law.repository.LegalSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LegalSourceIndexService {
    private final LegalSourceIndexRepository repository;
    private final LegalSourceRepository sources;
    private final com.MoleLaw_backend.law.repository.LawChunkIndexRepository chunks;

    @Transactional
    public LegalSourceIndex begin(LegalSource source, LawVectorClient client) {
        var identity = client.identity();
        var record = new LegalSourceIndex(source.getId(), source.getContentHash(), client.fingerprint(),
                client.collectionName(), identity.get("model").toString(), identity.get("revision").toString(),
                (Integer) identity.get("dimensions"));
        record.claim(com.MoleLaw_backend.law.util.LawEmbeddingInputHash.of(source.getExternalId(),
                source.getContentHash() + "\n" + client.fingerprint()));
        try {
            return repository.saveAndFlush(record);
        } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
            for (Throwable cause = conflict; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.sql.SQLException sql
                        && (sql.getErrorCode() == 1062 || "23505".equals(sql.getSQLState())))
                    throw new com.MoleLaw_backend.law.exception.LegalToolException(
                            com.MoleLaw_backend.law.exception.LegalToolException.Reason.BUSY, true);
            }
            throw conflict;
        }
    }

    @Transactional
    public void complete(String generation, int count) {
        var record = repository.findById(generation).orElseThrow();
        if (!"WRITING".equals(record.getStatus()) || count < 1) throw new IllegalStateException("Invalid index completion");
        record.complete(count);
    }

    @Transactional
    public void rememberChunks(String generation, java.util.List<com.MoleLaw_backend.law.entity.LawChunkIndex> references) {
        var record = repository.lockGeneration(generation).orElseThrow();
        if (!java.util.Set.of("WRITING", "COMPLETE").contains(record.getStatus())
                || references.isEmpty() || references.stream().anyMatch(row -> !generation.equals(row.getGeneration()))
                || references.stream().map(com.MoleLaw_backend.law.entity.LawChunkIndex::getPointId).distinct().count() != references.size())
            throw new IllegalArgumentException("Invalid chunk membership");
        var existing = chunks.findByGeneration(generation);
        if (existing.isEmpty()) {
            chunks.saveAllAndFlush(references);
        } else {
            var expected = references.stream().collect(java.util.stream.Collectors.toMap(
                    com.MoleLaw_backend.law.entity.LawChunkIndex::getPointId, row -> row));
            if (existing.size() != expected.size() || existing.stream().anyMatch(row -> {
                var target = expected.get(row.getPointId());
                return target == null || !target.getInputHash().equals(row.getInputHash())
                        || !target.getArticlePath().equals(row.getArticlePath()) || target.getChunkIndex() != row.getChunkIndex();
            })) throw new IllegalStateException("Chunk membership mismatch");
        }
    }

    @Transactional(readOnly = true)
    public java.util.List<com.MoleLaw_backend.law.entity.LawChunkIndex> chunkReferences(String generation) {
        return java.util.List.copyOf(chunks.findByGeneration(generation));
    }

    @Transactional
    public void fail(String generation) { repository.findById(generation).ifPresent(LegalSourceIndex::fail); }

    @Transactional(readOnly = true)
    public Optional<LegalSourceIndex> reusable(LegalSource source, String fingerprint) {
        return searchable(fingerprint).values().stream().filter(record -> {
            var indexed = sources.findById(record.getSourceId()).orElseThrow();
            return source.getExternalId().equals(indexed.getExternalId())
                    && source.getContentHash().equals(record.getContentHash());
        }).findFirst();
    }

    @Transactional(readOnly = true)
    public boolean matchesSource(LegalSourceIndex index, String externalId, String lawId, String effectiveDate, String url) {
        var source = sources.findById(index.getSourceId()).orElseThrow();
        return source.getExternalId().equals(externalId) && Objects.equals(source.getLawId(), lawId)
                && Objects.equals(source.getEffectiveDate(), effectiveDate) && source.getSourceUrl().equals(url);
    }

    /** Newest successful generation per MST. A failed replacement never hides the prior complete generation. */
    @Transactional(readOnly = true)
    public Map<String, LegalSourceIndex> searchable(String fingerprint) {
        Map<String, LegalSourceIndex> result = new LinkedHashMap<>();
        Map<String, java.time.Instant> newest = new HashMap<>();
        Map<String, String> generationByMst = new HashMap<>();
        for (var record : repository.findByConfigFingerprintAndStatusOrderByCompletedAtDescGenerationDesc(fingerprint, "COMPLETE")) {
            var source = sources.findById(record.getSourceId()).orElseThrow();
            if (!"LAW".equals(source.getSourceType()) || !record.getContentHash().equals(source.getContentHash())) continue;
            var previous = newest.get(source.getExternalId());
            if (previous == null || source.getCollectedAt().isAfter(previous)) {
                String oldGeneration = generationByMst.put(source.getExternalId(), record.getGeneration());
                if (oldGeneration != null) result.remove(oldGeneration);
                newest.put(source.getExternalId(), source.getCollectedAt());
                result.put(record.getGeneration(), record);
            }
        }
        return Map.copyOf(result);
    }
}
