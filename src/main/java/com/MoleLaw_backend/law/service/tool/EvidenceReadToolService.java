package com.MoleLaw_backend.law.service.tool;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.law.service.source.LegalSourceService;
import com.MoleLaw_backend.law.util.LawArticleChunker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/** Server-invoked only: IDs must belong to the current retrieval pool, never an arbitrary source UUID. */
@Service
public class EvidenceReadToolService {
    private final LegalSourceService sources;
    private final ObjectMapper mapper;

    public EvidenceReadToolService(LegalSourceService sources, ObjectMapper mapper) {
        this.sources = sources;
        this.mapper = mapper;
    }

    public List<EvidenceItem> readEvidence(List<EvidenceItem> authorized, List<String> requestedIds) {
        if (requestedIds == null || requestedIds.size() > 8
                || requestedIds.stream().distinct().count() != requestedIds.size())
            throw new IllegalArgumentException("Invalid evidence read request");
        var pool = new HashMap<String, EvidenceItem>();
        for (var item : authorized) {
            if (pool.putIfAbsent(item.id(), item) != null) throw new IllegalArgumentException("Duplicate evidence ID");
        }
        if (!pool.keySet().containsAll(requestedIds)) throw new IllegalArgumentException("Unknown evidence ID");
        var restored = new HashMap<String, List<LawArticleChunker.Chunk>>();
        var sourceCache = new HashMap<String, com.MoleLaw_backend.law.entity.LegalSource>();
        return requestedIds.stream().map(id -> {
            var item = pool.get(id);
            // These immutable API bodies were already validated by the AI Search tool.
            if ("LAW_AI_SEARCH".equals(item.kind())) return item;
            if (!"LAW_QDRANT".equals(item.kind()) || item.provenance() == null)
                throw new IllegalArgumentException("Unsupported evidence source");
            var provenance = item.provenance();
            var source = sourceCache.computeIfAbsent(provenance.sourceUuid(), sources::restore);
            if (!"LAW".equals(source.getSourceType()) || !Objects.equals(source.getExternalId(), provenance.mst())
                    || !Objects.equals(source.getLawId(), provenance.lawId())
                    || !Objects.equals(source.getEffectiveDate(), provenance.effectiveDate())
                    || !Objects.equals(source.getSourceUrl(), item.sourceUrl())
                    || !(source.getExternalId() + ":" + provenance.articlePath()).equals(item.sourceId()))
                throw new IllegalArgumentException("Evidence source identity mismatch");
            var articles = restored.computeIfAbsent(source.getId(), ignored -> {
                try {
                    String title = mapper.readTree(source.getRawJson()).path("기본정보").path("법령명_한글").asText();
                    return new LawArticleChunker(mapper).chunk(source.getRawJson(), title, 2000, 0);
                } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                    throw new IllegalArgumentException("Invalid saved law source");
                }
            });
            var article = articles.stream().filter(value -> value.unitId().equals(provenance.articlePath()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Saved article not found"));
            if (!article.articleText().equals(item.content()) || !article.title().equals(item.title()))
                throw new IllegalArgumentException("Evidence body differs from saved source");
            return new EvidenceItem(item.id(), item.kind(), item.sourceId(), article.title(), item.sourceUrl(),
                    article.articleText(), provenance);
        }).toList();
    }
}
