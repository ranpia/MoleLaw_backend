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

/** Internal, fixed-endpoint HTTP boundary; provider error bodies are never exposed. */
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
        this.embedding = client(properties.getEmbeddingBaseUrl(), 120_000, "");
        this.qdrant = client(properties.getQdrantBaseUrl(), 15_000, properties.getApiKey());
    }

    public List<List<Double>> embed(List<String> inputs, Runnable before, Runnable after) {
        try (var permit = embeddingWork.acquire()) {
            // The first HTTP before callback checks the deadline after queue waiting.
            return embedNow(inputs, before, after);
        }
    }

    private List<List<Double>> embedNow(List<String> inputs, Runnable before, Runnable after) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > 32 || inputs.stream().anyMatch(s -> s == null || s.isBlank())) {
            throw invalid();
        }
        JsonNode tags = request(embedding, "GET", "/api/tags", null, before, after);
        boolean pinned = false;
        for (JsonNode model : tags.path("models")) {
            if (properties.getModel().equals(model.path("name").asText())
                    && properties.getRevision().equals(model.path("digest").asText())) pinned = true;
        }
        if (!pinned) throw invalid();
        JsonNode result = request(embedding, "POST", "/api/embed", Map.of(
                "model", properties.getModel(), "input", inputs, "truncate", false,
                "options", Map.of("num_thread", properties.getThreads(), "num_gpu", 0)), before, after);
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

    public JsonNode query(List<Double> vector, Runnable before, Runnable after) {
        // Legacy direct callers cannot make uncommitted points visible.
        return query(vector, List.of("unpublished"), before, after);
    }

    public JsonNode query(List<Double> vector, List<String> generations, Runnable before, Runnable after) {
        if (vector == null || vector.size() != properties.getDimensions()
                || vector.stream().anyMatch(value -> value == null || !Double.isFinite(value))) throw invalid();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("query", vector);
        body.put("group_by", "unit_id");
        body.put("limit", 15);
        body.put("group_size", 1);
        body.put("with_payload", true);
        body.put("with_vector", false);
        List<Map<String, Object>> conditions = new ArrayList<>();
        conditions.add(Map.of("key", "source_type", "match", Map.of("value", "LAW")));
        if (generations == null || generations.isEmpty()) throw invalid();
        conditions.add(Map.of("key", "generation", "match", Map.of("any", List.copyOf(generations))));
        conditions.add(Map.of("key", "config_fingerprint", "match", Map.of("value", fingerprint())));
        body.put("filter", Map.of("must", conditions));
        JsonNode result = request(qdrant, "POST", path() + "/points/query/groups", body, before, after);
        JsonNode groups = result.path("result").path("groups");
        if (!groups.isArray() || groups.size() > 15) throw invalid();
        return groups;
    }

    public Map<String, Object> identity() {
        return Map.of("model", properties.getModel(), "revision", properties.getRevision(), "dimensions", properties.getDimensions(),
                "chunkSize", properties.getChunkSize(), "chunkOverlap", properties.getChunkOverlap(), "documentFormat", "title-newline-text-v1");
    }

    public String collectionName() { return properties.getCollectionName(); }

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
