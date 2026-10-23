package com.MoleLaw_backend.law.service.tool;

import com.MoleLaw_backend.law.dto.vector.LawSearchEvidence;
import com.MoleLaw_backend.law.service.source.LegalSourceIndexService;
import com.MoleLaw_backend.law.client.vector.LawVectorClient;
import com.MoleLaw_backend.law.exception.LegalToolException;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.MoleLaw_backend.law.exception.LegalToolException.Reason.INVALID_RESPONSE;

/** Read-only top-15 retrieval; does not call OpenLaw, collect additional sources or generate answers. */
public class LawRagSearchToolService {
    private final LawVectorClient client;
    private final LegalSourceIndexService indexes;
    private final com.MoleLaw_backend.law.service.source.LegalSourceService sources;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public LawRagSearchToolService(LawVectorClient client, LegalSourceIndexService indexes,
            com.MoleLaw_backend.law.service.source.LegalSourceService sources, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.client = client;
        this.indexes = indexes;
        this.sources = sources;
        this.mapper = mapper;
    }

    public List<String> indexedLawTitles() {
        return indexes.searchable(client.fingerprint()).values().stream()
                .map(index -> title(index.getSourceId())).filter(title -> !title.isBlank()).distinct().sorted().toList();
    }

    private String title(String sourceId) {
        try { return mapper.readTree(sources.restore(sourceId).getRawJson()).path("기본정보").path("법령명_한글").asText(); }
        catch (com.fasterxml.jackson.core.JsonProcessingException malformed) { throw invalid(); }
    }

    public List<LawSearchEvidence> searchTop15(LegalToolContext context, String question) {
        if (question == null || question.isBlank() || question.codePointCount(0, question.length()) > 2000) {
            throw new IllegalArgumentException("Question must contain 1 to 2000 characters");
        }
        synchronized (context) {
            context.authorize();
            context.configureVectorCallLimits(client.embeddingCallLimit(), client.indexingCallLimit());
            context.checkDeadline();
            if (!client.collectionAvailable(context::reserve, context::checkDeadline)) return List.of();
            var published = indexes.searchable(client.fingerprint());
            if (published.isEmpty()) return List.of();
            var exactUnits = new java.util.ArrayList<String>();
            for (var index : published.values()) {
                var source = sources.restore(index.getSourceId());
                for (String path : com.MoleLaw_backend.law.util.LawArticleQuery.paths(question, title(source.getId())))
                    if (exactUnits.size() < 15) exactUnits.add(source.getExternalId() + ":" + path);
            }
            List<Double> vector;
            try (var budget = context.embeddingTime(client.embeddingTimeout())) {
                vector = client.embed(List.of(question.strip()), context::reserveEmbedding, context::checkDeadline).get(0);
            }
            JsonNode groups = exactUnits.isEmpty()
                    ? client.query(vector, List.copyOf(published.keySet()), context::reserve, context::checkDeadline)
                    : client.query(vector, List.copyOf(published.keySet()), exactUnits, context::reserve, context::checkDeadline);
            if (!groups.isArray() || groups.size() > 15) throw invalid();
            List<LawSearchEvidence> result = new ArrayList<>();
            Set<String> units = new HashSet<>();
            double previous = Double.POSITIVE_INFINITY;
            for (JsonNode group : groups) {
                JsonNode hits = group.path("hits");
                if (!hits.isArray() || hits.size() != 1) throw invalid();
                JsonNode hit = hits.get(0), payload = hit.path("payload");
                double score = hit.path("score").asDouble(Double.NaN);
                String unit = required(payload, "unit_id");
                String generation = required(payload, "generation");
                if (!exactUnits.isEmpty() && !exactUnits.contains(unit)) throw invalid();
                var index = published.get(generation);
                if (index == null || !index.getSourceId().equals(required(payload, "reference_id"))
                        || !index.getContentHash().equals(required(payload, "content_hash"))
                        || !index.getConfigFingerprint().equals(required(payload, "config_fingerprint"))
                        || !index.getModelRevision().equals(required(payload, "model_revision"))) throw invalid();
                if (!indexes.matchesSource(index, required(payload, "external_id"), nullable(payload, "law_id"),
                        nullable(payload, "effective_date"), required(payload, "source_url"))) throw invalid();
                if (!Double.isFinite(score) || score > previous || !units.add(unit)
                        || !unit.equals(group.path("id").asText())
                        || !"LAW".equals(payload.path("source_type").asText())) throw invalid();
                previous = score;
                result.add(new LawSearchEvidence(unit, required(payload, "reference_id"), required(payload, "external_id"),
                        payload.path("law_id").asText(), payload.path("effective_date").asText(),
                        required(payload, "title"), required(payload, "text"), required(payload, "article_text"),
                        required(payload, "source_url"), score, index.getModelRevision(), index.getConfigFingerprint(), generation));
            }
            return List.copyOf(result);
        }
    }

    private static String required(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isTextual() || value.asText().isBlank()) throw invalid();
        return value.asText();
    }

    private static LegalToolException invalid() { return new LegalToolException(INVALID_RESPONSE, false); }
    private static String nullable(JsonNode node, String field) {
        String value = node.path(field).asText();
        return value.isBlank() ? null : value;
    }
}
