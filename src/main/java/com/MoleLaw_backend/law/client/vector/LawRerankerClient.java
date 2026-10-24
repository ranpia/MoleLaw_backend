package com.MoleLaw_backend.law.client.vector;

import com.MoleLaw_backend.law.config.LawRerankerProperties;
import com.MoleLaw_backend.law.dto.vector.LawSearchEvidence;
import com.MoleLaw_backend.law.exception.LegalToolException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Local cross-encoder boundary. Ranking changes; original evidence and dense scores do not. */
public class LawRerankerClient {
    private final LawRerankerProperties properties;
    private final ObjectMapper mapper;
    private final RestClient http;

    public LawRerankerClient(LawRerankerProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        URI uri = URI.create(properties.getBaseUrl());
        if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("Invalid reranker URL");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(properties.getReadTimeoutMillis());
        this.http = RestClient.builder().baseUrl(properties.getBaseUrl()).requestFactory(factory).build();
    }

    public boolean enabled() { return properties.isEnabled(); }
    public int candidateLimit() { return properties.getCandidateLimit(); }

    public List<LawSearchEvidence> rerank(String query, List<LawSearchEvidence> candidates,
            Runnable before, Runnable after) {
        if (candidates.isEmpty()) return List.of();
        if (query == null || query.isBlank() || query.codePointCount(0, query.length()) > 2000
                || candidates.size() > candidateLimit()
                || candidates.stream().map(LawSearchEvidence::unitId).distinct().count() != candidates.size()) throw invalid();
        List<Map<String, String>> documents = candidates.stream()
                .map(hit -> Map.of("id", hit.unitId(), "text", hit.title() + "\n" + hit.text())).toList();
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(Map.of("query", query, "documents", documents,
                    "model", properties.getModel(), "revision", properties.getRevision()));
        } catch (java.io.IOException failure) { throw invalid(); }
        if (body.length > 1_048_576) throw invalid();
        before.run();
        JsonNode response;
        try {
            response = http.post().uri("/api/rerank").contentType(MediaType.APPLICATION_JSON)
                    .contentLength(body.length).body(body).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException failure) {
            after.run();
            throw new LegalToolException(LegalToolException.Reason.API_FAILURE,
                    failure.getStatusCode().value() == 429 || failure.getStatusCode().is5xxServerError());
        } catch (RuntimeException failure) {
            after.run();
            throw new LegalToolException(LegalToolException.Reason.API_FAILURE, true);
        }
        after.run();
        if (response == null || !properties.getModel().equals(response.path("model").asText())
                || !properties.getRevision().equals(response.path("revision").asText())) throw invalid();
        JsonNode results = response.path("results");
        if (!results.isArray() || results.size() != candidates.size()) throw invalid();
        Map<String, Double> scores = new HashMap<>();
        var ids = candidates.stream().map(LawSearchEvidence::unitId).collect(java.util.stream.Collectors.toSet());
        for (JsonNode item : results) {
            String id = item.path("id").asText();
            double score = item.path("score").asDouble(Double.NaN);
            if (!ids.contains(id) || !item.path("score").isNumber() || !Double.isFinite(score)
                    || scores.putIfAbsent(id, score) != null) throw invalid();
        }
        return candidates.stream().sorted(Comparator.comparingDouble(
                (LawSearchEvidence hit) -> scores.get(hit.unitId())).reversed()).limit(15).toList();
    }

    private static LegalToolException invalid() {
        return new LegalToolException(LegalToolException.Reason.INVALID_RESPONSE, false);
    }
}
