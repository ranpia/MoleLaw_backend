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

/** Execution-scoped writer; embeds once through the existing local HTTP provider. */
public record LawQdrantDocumentWriter(LawVectorClient client, LawVectorProperties properties,
        LegalSource source, LegalSourceIndex attempt, Runnable before, Runnable after) implements DocumentWriter {
    @Override
    public void accept(List<Document> documents) {
        if (documents == null || documents.isEmpty()) throw new IllegalArgumentException("No law documents");
        // Embed everything before writing anything. Never feed precomputed vectors to VectorStore.add().
        List<List<Double>> vectors = new ArrayList<>();
        for (int start = 0; start < documents.size(); start += properties.getBatchSize()) {
            var batch = documents.subList(start, Math.min(start + properties.getBatchSize(), documents.size()));
            vectors.addAll(client.embed(batch.stream()
                    .map(c -> c.getMetadata().get("title") + "\n" + c.getText()).toList(),
                    before, after));
        }
        client.ensureCollection(before, after);
        List<Map<String, Object>> points = new ArrayList<>();
        String identity = attempt.getGeneration();
        for (int i = 0; i < documents.size(); i++) {
            var chunk = documents.get(i);
            String unitId = source.getExternalId() + ":" + chunk.getMetadata().get("article_id");
            String id = UUID.nameUUIDFromBytes((identity + ":" + chunk.getMetadata().get("article_id") + ":" + chunk.getMetadata().get("chunk_index"))
                    .getBytes(StandardCharsets.UTF_8)).toString();
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
    }
}
