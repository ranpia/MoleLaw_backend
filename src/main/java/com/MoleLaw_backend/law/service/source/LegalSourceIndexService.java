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

    @Transactional
    public LegalSourceIndex begin(LegalSource source, LawVectorClient client) {
        var identity = client.identity();
        return repository.saveAndFlush(new LegalSourceIndex(source.getId(), source.getContentHash(), client.fingerprint(),
                client.collectionName(), identity.get("model").toString(), identity.get("revision").toString(),
                (Integer) identity.get("dimensions")));
    }

    @Transactional
    public void complete(String generation, int count) {
        var record = repository.findById(generation).orElseThrow();
        if (!"WRITING".equals(record.getStatus()) || count < 1) throw new IllegalStateException("Invalid index completion");
        record.complete(count);
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
