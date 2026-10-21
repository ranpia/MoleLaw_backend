package com.MoleLaw_backend.law.service.tool;

import com.MoleLaw_backend.law.config.LawVectorProperties;
import com.MoleLaw_backend.law.entity.LegalSource;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.MoleLaw_backend.law.service.tool.LegalToolException.Reason.*;

/** Explicit server tool. Its context and source references must come from an authorized execution. */
public class LawVectorizationToolService {
    private final LawVectorClient client;
    private final LawVectorProperties properties;
    private final LawArticleChunker chunker;
    private final LegalSourceService sources;

    public LawVectorizationToolService(LawVectorClient client, LawVectorProperties properties,
                                      ObjectMapper mapper, LegalSourceService sources) {
        this.client = client;
        this.properties = properties;
        this.chunker = new LawArticleChunker(mapper);
        this.sources = sources;
    }

    public record IndexResult(String referenceId, String collection, int articleCount, int chunkCount) { }

    public IndexResult vectorizeLaw(LegalToolContext context, String sourceReferenceId) {
        synchronized (context) {
            context.authorize();
            context.checkDeadline();
            var detail = context.details.values().stream()
                    .filter(d -> d.referenceId().equals(sourceReferenceId)).findFirst()
                    .orElseThrow(() -> new LegalToolException(UNKNOWN_ID, false));
            LegalSource source = sources.restore(sourceReferenceId);
            if (!"LAW".equals(source.getSourceType()) || !detail.externalId().equals(source.getExternalId())) {
                throw new LegalToolException(UNKNOWN_ID, false);
            }
            List<LawArticleChunker.Chunk> chunks = chunker.chunk(source.getRawJson(), detail.title(),
                    properties.getChunkSize(), properties.getChunkOverlap());
            if (chunks.isEmpty()) throw new LegalToolException(INVALID_RESPONSE, false);
            // Embed everything before writing anything. Never feed precomputed vectors to VectorStore.add().
            List<List<Double>> vectors = new ArrayList<>();
            for (int start = 0; start < chunks.size(); start += properties.getBatchSize()) {
                var batch = chunks.subList(start, Math.min(start + properties.getBatchSize(), chunks.size()));
                vectors.addAll(client.embed(batch.stream()
                        .map(c -> c.title() + "\n" + c.text()).toList(),
                        context::reserve, context::checkDeadline));
            }
            client.ensureCollection(context::reserve, context::checkDeadline);
            List<Map<String, Object>> points = new ArrayList<>();
            String identity = source.getExternalId() + ":" + source.getContentHash();
            for (int i = 0; i < chunks.size(); i++) {
                var chunk = chunks.get(i);
                String unitId = source.getExternalId() + ":" + chunk.unitId();
                String id = UUID.nameUUIDFromBytes((identity + ":" + chunk.unitId() + ":" + chunk.chunkIndex())
                        .getBytes(StandardCharsets.UTF_8)).toString();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("source_type", "LAW");
                payload.put("unit_id", unitId);
                payload.put("reference_id", source.getId());
                payload.put("external_id", source.getExternalId());
                payload.put("law_id", source.getLawId() == null ? "" : source.getLawId());
                payload.put("effective_date", source.getEffectiveDate() == null ? "" : source.getEffectiveDate());
                payload.put("title", chunk.title());
                payload.put("text", chunk.text());
                payload.put("article_text", chunk.articleText());
                payload.put("source_url", source.getSourceUrl());
                payload.put("content_hash", source.getContentHash());
                points.add(Map.of("id", id, "vector", vectors.get(i), "payload", payload));
            }
            for (int start = 0; start < points.size(); start += properties.getBatchSize()) {
                client.upsert(points.subList(start, Math.min(start + properties.getBatchSize(), points.size())),
                        context::reserve, context::checkDeadline);
            }
            return new IndexResult(source.getId(), properties.getCollectionName(),
                    (int) chunks.stream().map(LawArticleChunker.Chunk::unitId).distinct().count(), chunks.size());
        }
    }
}
