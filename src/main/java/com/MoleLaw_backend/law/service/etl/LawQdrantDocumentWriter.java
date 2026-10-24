package com.MoleLaw_backend.law.service.etl;
import com.MoleLaw_backend.law.client.vector.LawVectorClient;
import com.MoleLaw_backend.law.config.LawVectorProperties;
import com.MoleLaw_backend.law.entity.LegalSource;
import com.MoleLaw_backend.law.entity.LegalSourceIndex;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import com.MoleLaw_backend.law.service.tool.LegalToolContext;
import com.MoleLaw_backend.law.exception.LegalToolException;
import lombok.extern.slf4j.Slf4j;

/** Execution-scoped writer; embeds once through the existing local HTTP provider. */
@Slf4j
public record LawQdrantDocumentWriter(LawVectorClient client, LawVectorProperties properties,
        LegalSource source, LegalSourceIndex attempt, Runnable before, Runnable after,
        Supplier<LegalToolContext.EmbeddingTime> embeddingTime, Runnable beforeEmbedding,
        Map<String, List<Double>> reusableVectors) implements DocumentWriter {
    public LawQdrantDocumentWriter(LawVectorClient client, LawVectorProperties properties,
            LegalSource source, LegalSourceIndex attempt, Runnable before, Runnable after,
            Supplier<LegalToolContext.EmbeddingTime> embeddingTime, Runnable beforeEmbedding) {
        this(client, properties, source, attempt, before, after, embeddingTime, beforeEmbedding, Map.of());
    }
    @Override
    public void accept(List<Document> documents) {
        if (documents == null || documents.isEmpty()) throw new IllegalArgumentException("No law documents");
        // Embed everything before writing anything. Never feed precomputed vectors to VectorStore.add().
        List<List<Double>> vectors = new ArrayList<>(java.util.Collections.nCopies(documents.size(), null));
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            var document = documents.get(i);
            var vector = reusableVectors.get(com.MoleLaw_backend.law.util.LawEmbeddingInputHash.of(
                    document.getMetadata().get("title").toString(), document.getText()));
            if (vector == null) missing.add(i); else vectors.set(i, vector);
        }
        var session = client.newEmbeddingSession();
        for (int start = 0; start < missing.size(); start += properties.getBatchSize()) {
            var positions = missing.subList(start, Math.min(start + properties.getBatchSize(), missing.size()));
            var batch = positions.stream().map(documents::get).toList();
            try (var budget = embeddingTime.get()) {
                var embedded = session.embed(batch.stream()
                        .map(c -> c.getMetadata().get("title") + "\n" + c.getText()).toList(),
                        beforeEmbedding, after);
                for (int i = 0; i < positions.size(); i++) vectors.set(positions.get(i), embedded.get(i));
            }
        }
        log.info("Law incremental vectors chunks={} reused={} embedded={}",
                documents.size(), documents.size() - missing.size(), missing.size());
        client.ensureCollection(before, after);
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            var chunk = documents.get(i);
            String unitId = source.getExternalId() + ":" + chunk.getMetadata().get("article_id");
            String id = pointId(chunk);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("source_type", "LAW");
            payload.put("unit_id", unitId);
            payload.put("reference_id", source.getId());
            payload.put("external_id", source.getExternalId());
            payload.put("law_id", source.getLawId() == null ? "" : source.getLawId());
            payload.put("effective_date", source.getEffectiveDate() == null ? "" : source.getEffectiveDate());
            payload.put("title", chunk.getMetadata().get("title"));
            payload.put("text", chunk.getText());
            payload.put("article_text", chunk.getMetadata().get("article_text"));
            payload.put("source_url", source.getSourceUrl());
            payload.put("content_hash", source.getContentHash());
            payload.put("generation", attempt.getGeneration());
            payload.put("config_fingerprint", attempt.getConfigFingerprint());
            payload.put("model_revision", attempt.getModelRevision());
            points.add(Map.of("id", id, "vector", vectors.get(i), "payload", payload));
        }
        for (int start = 0; start < points.size(); start += properties.getBatchSize()) {
            client.upsert(points.subList(start, Math.min(start + properties.getBatchSize(), points.size())),
                    before, after);
        }
        if (!verify(documents)) throw new LegalToolException(LegalToolException.Reason.INDEX_VERIFICATION_FAILED, false);
    }

    public boolean verify(List<Document> documents) {
        if (documents == null || documents.isEmpty()) throw new IllegalArgumentException("No law documents");
        Map<String, String> units = new LinkedHashMap<>();
        for (var document : documents) {
            String previous = units.put(pointId(document), source.getExternalId() + ":" + document.getMetadata().get("article_id"));
            if (previous != null) throw new LegalToolException(LegalToolException.Reason.INDEX_VERIFICATION_FAILED, false);
        }
        Map<String, Object> payload = Map.of("source_type", "LAW", "reference_id", source.getId(),
                "external_id", source.getExternalId(), "content_hash", source.getContentHash(),
                "generation", attempt.getGeneration(), "config_fingerprint", attempt.getConfigFingerprint(),
                "model_revision", attempt.getModelRevision(), "law_id", source.getLawId() == null ? "" : source.getLawId(),
                "effective_date", source.getEffectiveDate() == null ? "" : source.getEffectiveDate());
        boolean valid = client.verifyIndex(units, payload, before, after);
        log.info("Law index verification generation={} expectedChunks={} verified={}", attempt.getGeneration(), documents.size(), valid);
        return valid;
    }

    private String pointId(Document chunk) {
        return pointId(attempt.getGeneration(), chunk);
    }

    public static List<com.MoleLaw_backend.law.entity.LawChunkIndex> references(String generation, List<Document> documents) {
        return documents.stream().map(document -> new com.MoleLaw_backend.law.entity.LawChunkIndex(
                pointId(generation, document), generation, document.getMetadata().get("article_id").toString(),
                ((Number) document.getMetadata().get("chunk_index")).intValue(),
                com.MoleLaw_backend.law.util.LawEmbeddingInputHash.of(
                        document.getMetadata().get("title").toString(), document.getText()))).toList();
    }

    public static String pointId(String generation, Document chunk) {
        return UUID.nameUUIDFromBytes((generation + ":" + chunk.getMetadata().get("article_id")
                + ":" + chunk.getMetadata().get("chunk_index")).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
