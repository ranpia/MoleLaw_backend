package com.MoleLaw_backend.law.service.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.MoleLaw_backend.law.service.tool.LegalToolException.Reason.INVALID_RESPONSE;

/** Read-only top-15 retrieval; does not call OpenLaw, collect additional sources or generate answers. */
public class LawRagSearchToolService {
    private final LawVectorClient client;

    public LawRagSearchToolService(LawVectorClient client) {
        this.client = client;
    }

    public record Evidence(String unitId, String referenceId, String externalId, String lawId,
                           String effectiveDate, String title, String text, String articleText,
                           String sourceUrl, double score) { }

    public List<Evidence> searchTop15(LegalToolContext context, String question) {
        if (question == null || question.isBlank() || question.codePointCount(0, question.length()) > 2000) {
            throw new IllegalArgumentException("Question must contain 1 to 2000 characters");
        }
        synchronized (context) {
            context.authorize();
            context.checkDeadline();
            if (!client.collectionAvailable(context::reserve, context::checkDeadline)) return List.of();
            var vector = client.embed(List.of(question.strip()), context::reserve, context::checkDeadline).get(0);
            JsonNode groups = client.query(vector, context::reserve, context::checkDeadline);
            if (!groups.isArray() || groups.size() > 15) throw invalid();
            List<Evidence> result = new ArrayList<>();
            Set<String> units = new HashSet<>();
            double previous = Double.POSITIVE_INFINITY;
            for (JsonNode group : groups) {
                JsonNode hits = group.path("hits");
                if (!hits.isArray() || hits.size() != 1) throw invalid();
                JsonNode hit = hits.get(0), payload = hit.path("payload");
                double score = hit.path("score").asDouble(Double.NaN);
                String unit = required(payload, "unit_id");
                if (!Double.isFinite(score) || score > previous || !units.add(unit)
                        || !unit.equals(group.path("id").asText())
                        || !"LAW".equals(payload.path("source_type").asText())) throw invalid();
                previous = score;
                result.add(new Evidence(unit, required(payload, "reference_id"), required(payload, "external_id"),
                        payload.path("law_id").asText(), payload.path("effective_date").asText(),
                        required(payload, "title"), required(payload, "text"), required(payload, "article_text"),
                        required(payload, "source_url"), score));
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
}
