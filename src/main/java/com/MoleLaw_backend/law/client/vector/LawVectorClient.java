package com.MoleLaw_backend.law.client.vector;

import com.MoleLaw_backend.law.util.EmbeddingWorkLimiter;
import com.MoleLaw_backend.law.exception.LegalToolException;

import com.MoleLaw_backend.law.config.LawVectorProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/** Internal, fixed-endpoint HTTP boundary; provider error bodies are never exposed. */
@Slf4j
public class LawVectorClient {
    private final LawVectorProperties properties;
    private final ObjectMapper mapper;
    private final RestClient embedding;
    private final RestClient qdrant;
    private final EmbeddingWorkLimiter embeddingWork;

    public LawVectorClient(LawVectorProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.embeddingWork = new EmbeddingWorkLimiter(properties.getEmbeddingConcurrency(),
                properties.getEmbeddingQueueCapacity(), properties.getEmbeddingWaitMillis());
        validateConfiguration();
        this.embedding = client(properties.getEmbeddingBaseUrl(), properties.getEmbeddingReadTimeoutMillis(), "");
        this.qdrant = client(properties.getQdrantBaseUrl(), 15_000, properties.getApiKey());
    }

    public List<List<Double>> embed(List<String> inputs, Runnable before, Runnable after) {
        return newEmbeddingSession().embed(inputs, before, after);
    }

    /** Revision verification lasts only for this bounded indexing operation, never globally. */
    public EmbeddingSession newEmbeddingSession() { return new EmbeddingSession(); }

    public final class EmbeddingSession {
        private boolean verified;
        private EmbeddingSession() { }
        public List<List<Double>> embed(List<String> inputs, Runnable before, Runnable after) {
            try (var permit = embeddingWork.acquire()) {
                // The first HTTP callback checks the deadline after queue waiting.
                var result = embedNow(inputs, before, after, !verified);
                verified = true;
                return result;
            } catch (RuntimeException failure) {
                verified = false;
                throw failure;
            }
        }
    }

    private List<List<Double>> embedNow(List<String> inputs, Runnable before, Runnable after, boolean verifyModel) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > 32 || inputs.stream().anyMatch(s -> s == null || s.isBlank())) {
            throw invalid();
        }
        if (verifyModel) {
            JsonNode tags = request(embedding, "GET", "/api/tags", null, before, after);
            boolean pinned = false;
            for (JsonNode model : tags.path("models")) {
                if (properties.getModel().equals(model.path("name").asText())
                        && properties.getRevision().equals(model.path("digest").asText())) pinned = true;
            }
            if (!pinned) throw invalid();
        }
        JsonNode result = request(embedding, "POST", "/api/embed", Map.of(
                "model", properties.getModel(), "input", inputs, "truncate", false,
                "options", Map.of("num_thread", properties.getThreads(), "num_gpu", properties.getGpuCount())), before, after);
        JsonNode vectors = result.path("embeddings");
        if (!properties.getModel().equals(result.path("model").asText()) || !vectors.isArray() || vectors.size() != inputs.size()) throw invalid();
        List<List<Double>> output = new ArrayList<>();
        for (JsonNode vector : vectors) {
            if (!vector.isArray() || vector.size() != properties.getDimensions()) throw invalid();
            List<Double> values = new ArrayList<>();
            double norm = 0;
            for (JsonNode value : vector) {
                double number = value.asDouble(Double.NaN);
                if (!value.isNumber() || !Double.isFinite(number)) throw invalid();
                values.add(number);
                norm += number * number;
            }
            if (Math.abs(Math.sqrt(norm) - 1.0) > 0.01) throw invalid();
            output.add(List.copyOf(values));
        }
        return List.copyOf(output);
    }

    public void ensureCollection(Runnable before, Runnable after) {
        JsonNode collection;
        try {
            collection = request(qdrant, "GET", path(), null, before, after);
        } catch (MissingCollection missing) {
            request(qdrant, "PUT", path(), Map.of("vectors", Map.of("size", properties.getDimensions(), "distance", "Cosine"),
                    "metadata", identity()), before, after);
            collection = request(qdrant, "GET", path(), null, before, after);
        }
        validateCollection(collection);
    }

    /** Retrieval never creates a collection; an uninitialized store has no evidence. */
    public boolean collectionAvailable(Runnable before, Runnable after) {
        try {
            validateCollection(request(qdrant, "GET", path(), null, before, after));
            return true;
        } catch (MissingCollection missing) {
            return false;
        }
    }

    private void validateCollection(JsonNode collection) {
        JsonNode config = collection.path("result").path("config");
        JsonNode vectors = config.path("params").path("vectors");
        if (vectors.path("size").asInt(-1) != properties.getDimensions()
                || !"Cosine".equals(vectors.path("distance").asText())
                || !mapper.valueToTree(identity()).equals(config.path("metadata"))) throw invalid();
    }

    public void upsert(List<Map<String, Object>> points, Runnable before, Runnable after) {
        if (points == null || points.isEmpty()) throw invalid();
        Map<String, Object> body = Map.of("points", points);
        try {
            if (mapper.writeValueAsBytes(body).length > 16 * 1024 * 1024) throw invalid();
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw invalid();
        }
        JsonNode result = request(qdrant, "PUT", path() + "/points?wait=true", body, before, after);
        if (!"completed".equals(result.path("result").path("status").asText())) throw invalid();
    }

    /** Read-after-write verification. A transport error is not evidence that an index is missing. */
    public boolean verifyIndex(Map<String, String> expectedUnits, Map<String, Object> expectedPayload,
            Runnable before, Runnable after) {
        if (expectedUnits == null || expectedUnits.isEmpty() || expectedPayload == null
                || !expectedPayload.containsKey("generation")) throw invalid();
        var filter = Map.of("must", List.of(Map.of("key", "generation", "match",
                Map.of("value", expectedPayload.get("generation")))));
        JsonNode count = request(qdrant, "POST", path() + "/points/count",
                Map.of("filter", filter, "exact", true), before, after).path("result").path("count");
        if (!count.isIntegralNumber() || !count.canConvertToLong() || count.asLong() < 0) throw invalid();
        if (count.asLong() != expectedUnits.size()) {
            log.warn("Law index verification mismatch reason=POINT_COUNT expected={} stored={}", expectedUnits.size(), count.asLong());
            return false;
        }
        var ids = new ArrayList<>(expectedUnits.keySet());
        var fields = new ArrayList<>(expectedPayload.keySet());
        fields.add("unit_id");
        for (int start = 0; start < ids.size(); start += 256) {
            var batch = ids.subList(start, Math.min(start + 256, ids.size()));
            JsonNode points = request(qdrant, "POST", path() + "/points",
                    Map.of("ids", batch, "with_payload", fields, "with_vector", true), before, after).path("result");
            if (!points.isArray()) throw invalid();
            if (points.size() != batch.size()) return false;
            var seen = new java.util.HashSet<String>();
            for (JsonNode point : points) {
                String id = point.path("id").asText();
                if (!batch.contains(id) || !seen.add(id)
                        || !expectedUnits.get(id).equals(point.path("payload").path("unit_id").asText())) return false;
                JsonNode vector = point.path("vector");
                if (!vector.isArray() || vector.size() != properties.getDimensions()) return false;
                double norm = 0;
                for (JsonNode component : vector) {
                    double number = component.asDouble(Double.NaN);
                    if (!component.isNumber() || !Double.isFinite(number)) return false;
                    norm += number * number;
                }
                if (Math.abs(Math.sqrt(norm) - 1.0) > 0.01) return false;
                for (var field : expectedPayload.entrySet()) {
                    if (!mapper.valueToTree(field.getValue()).equals(point.path("payload").path(field.getKey()))) return false;
                }
            }
        }
        return true;
    }

    /** Reuse only exact inputs from a server-authorized COMPLETE snapshot. Missing/corrupt points are reembedded. */
    public Map<String, List<Double>> reusableVectors(Map<String, String> expectedInputs, Map<String, Object> expectedPayload,
            Runnable before, Runnable after) {
        if (expectedInputs.isEmpty() || !expectedPayload.containsKey("generation")) throw invalid();
        Map<String, List<Double>> result = new java.util.HashMap<>();
        var ids = new ArrayList<>(expectedInputs.keySet());
        var fields = new ArrayList<>(expectedPayload.keySet());
        fields.add("title"); fields.add("text");
        var seen = new java.util.HashSet<String>();
        for (int start = 0; start < ids.size(); start += 256) {
            var batch = ids.subList(start, Math.min(start + 256, ids.size()));
            JsonNode points = request(qdrant, "POST", path() + "/points",
                    Map.of("ids", batch, "with_payload", fields, "with_vector", true), before, after).path("result");
            if (!points.isArray()) throw invalid();
            for (JsonNode point : points) {
                String id = point.path("id").asText();
                if (!batch.contains(id) || !seen.add(id)) throw invalid();
                JsonNode payload = point.path("payload"), vector = point.path("vector");
                boolean matches = expectedPayload.entrySet().stream()
                        .allMatch(entry -> mapper.valueToTree(entry.getValue()).equals(payload.path(entry.getKey())));
                if (!matches || !payload.path("title").isTextual() || !payload.path("text").isTextual()
                        || !expectedInputs.get(id).equals(com.MoleLaw_backend.law.util.LawEmbeddingInputHash.of(
                                payload.path("title").asText(), payload.path("text").asText()))
                        || !vector.isArray() || vector.size() != properties.getDimensions()) continue;
                List<Double> values = new ArrayList<>();
                double norm = 0;
                for (JsonNode component : vector) {
                    double value = component.asDouble(Double.NaN);
                    if (!component.isNumber() || !Double.isFinite(value)) { values.clear(); break; }
                    values.add(value); norm += value * value;
                }
                if (values.size() == properties.getDimensions() && Math.abs(Math.sqrt(norm) - 1.0) <= 0.01)
                    result.put(expectedInputs.get(id), List.copyOf(values));
            }
        }
        return Map.copyOf(result);
    }

    public JsonNode query(List<Double> vector, Runnable before, Runnable after) {
        // Legacy direct callers cannot make uncommitted points visible.
        return query(vector, List.of("unpublished"), before, after);
    }

    public JsonNode query(List<Double> vector, List<String> generations, Runnable before, Runnable after) {
        return query(vector, generations, List.of(), before, after);
    }

    public JsonNode query(List<Double> vector, List<String> generations, List<String> unitIds, Runnable before, Runnable after) {
        return query(vector, generations, unitIds, 15, before, after);
    }

    public JsonNode query(List<Double> vector, List<String> generations, List<String> unitIds, int limit,
            Runnable before, Runnable after) {
        if (limit < 1 || limit > 100) throw invalid();
        if (vector == null || vector.size() != properties.getDimensions()
                || vector.stream().anyMatch(value -> value == null || !Double.isFinite(value))) throw invalid();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("query", vector);
        body.put("group_by", "unit_id");
        body.put("limit", limit);
        body.put("group_size", 1);
        body.put("with_payload", true);
        body.put("with_vector", false);
        List<Map<String, Object>> conditions = new ArrayList<>();
        conditions.add(Map.of("key", "source_type", "match", Map.of("value", "LAW")));
        if (generations == null || generations.isEmpty()) throw invalid();
        conditions.add(Map.of("key", "generation", "match", Map.of("any", List.copyOf(generations))));
        conditions.add(Map.of("key", "config_fingerprint", "match", Map.of("value", fingerprint())));
        if (!unitIds.isEmpty()) {
            if (unitIds.size() > 15 || unitIds.stream().anyMatch(id -> id == null || !id.matches("[0-9]{1,20}:[0-9]{1,8}(-[0-9]{1,8})?")))
                throw invalid();
            conditions.add(Map.of("key", "unit_id", "match", Map.of("any", List.copyOf(unitIds))));
        }
        body.put("filter", Map.of("must", conditions));
        JsonNode result = request(qdrant, "POST", path() + "/points/query/groups", body, before, after);
        JsonNode groups = result.path("result").path("groups");
        if (!groups.isArray() || groups.size() > limit) throw invalid();
        return groups;
    }

    public Map<String, Object> identity() {
        return Map.of("model", properties.getModel(), "revision", properties.getRevision(), "dimensions", properties.getDimensions(),
                "chunkSize", properties.getChunkSize(), "chunkOverlap", properties.getChunkOverlap(), "documentFormat", "title-newline-text-v1");
    }

    public String collectionName() { return properties.getCollectionName(); }
    public java.time.Duration embeddingTimeout() { return java.time.Duration.ofSeconds(properties.getEmbeddingTimeoutSeconds()); }
    public int embeddingCallLimit() { return properties.getEmbeddingMaxCalls(); }
    public int indexingCallLimit() { return properties.getIndexingMaxCalls(); }

    public String fingerprint() {
        try {
            var stable = new java.util.TreeMap<String, Object>(identity());
            stable.put("collection", properties.getCollectionName());
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(stable)));
        } catch (java.security.NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Cannot fingerprint vector configuration", failure);
        }
    }

    private JsonNode request(RestClient client, String method, String path, Object body, Runnable before, Runnable after) {
        before.run();
        try {
            RestClient.RequestBodySpec spec = client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path);
            if (body != null) {
                // The Python embedding server rejects chunked requests without Content-Length.
                byte[] encoded = mapper.writeValueAsBytes(body);
                spec.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .contentLength(encoded.length).body(encoded);
            }
            JsonNode response = spec.retrieve().body(JsonNode.class);
            after.run();
            if (response == null) throw invalid();
            return response;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw invalid();
        } catch (RestClientResponseException failure) {
            after.run();
            if (failure.getStatusCode().value() == 404 && method.equals("GET") && path.equals(path())) throw new MissingCollection();
            throw new LegalToolException(LegalToolException.Reason.API_FAILURE,
                    failure.getStatusCode().value() == 429 || failure.getStatusCode().is5xxServerError());
        } catch (LegalToolException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            after.run();
            throw new LegalToolException(LegalToolException.Reason.API_FAILURE, true);
        }
    }

    private String path() { return "/collections/" + properties.getCollectionName(); }

    private RestClient client(String url, int readTimeout, String apiKey) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(readTimeout);
        RestClient.Builder builder = RestClient.builder().baseUrl(url).requestFactory(factory);
        if (apiKey != null && !apiKey.isBlank()) builder.defaultHeader("api-key", apiKey);
        return builder.build();
    }

    private void validateConfiguration() {
        if (properties.getGpuCount() < 0 || properties.getGpuCount() > 1)
            throw new IllegalArgumentException("Embedding GPU count must be 0 or 1");
        if (properties.getEmbeddingMaxCalls() < 1 || properties.getEmbeddingMaxCalls() > 100000
                || properties.getIndexingMaxCalls() < 1 || properties.getIndexingMaxCalls() > 100000)
            throw new IllegalArgumentException("Invalid vector call limits");
        if (properties.getEmbeddingTimeoutSeconds() < 1 || properties.getEmbeddingTimeoutSeconds() > 86400
                || properties.getEmbeddingReadTimeoutMillis() < 1 || properties.getEmbeddingReadTimeoutMillis() > 3_600_000) {
            throw new IllegalArgumentException("Invalid embedding time limits");
        }
        for (String value : List.of(properties.getEmbeddingBaseUrl(), properties.getQdrantBaseUrl())) {
            URI uri = URI.create(value);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IllegalArgumentException("Invalid vector service URL");
        }
        if (!properties.getCollectionName().matches("molelaw_service_[a-z0-9_]{1,80}")
                || properties.getDimensions() < 1 || properties.getModel().isBlank() || properties.getRevision().isBlank()
                || properties.getModel().length() > 255 || properties.getRevision().length() > 255
                || properties.getThreads() < 1 || properties.getBatchSize() < 1 || properties.getBatchSize() > 32
                || properties.getChunkSize() < 1 || properties.getChunkOverlap() < 0
                || properties.getChunkOverlap() >= properties.getChunkSize()) throw new IllegalArgumentException("Invalid vector tool configuration");
    }

    private LegalToolException invalid() { return new LegalToolException(LegalToolException.Reason.INVALID_RESPONSE, false); }
    private static final class MissingCollection extends RuntimeException { }
}
