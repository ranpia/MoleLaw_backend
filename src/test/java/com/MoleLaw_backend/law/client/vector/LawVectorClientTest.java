package com.MoleLaw_backend.law.client.vector;
import com.MoleLaw_backend.law.exception.LegalToolException;

import com.MoleLaw_backend.law.config.LawVectorProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real loopback HTTP exercises serialization, request construction and error mapping; no live model. */
class LawVectorClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Request> requests = new ArrayList<>();
    private final AtomicInteger before = new AtomicInteger();
    private final AtomicInteger after = new AtomicInteger();
    private HttpServer server;
    private LawVectorProperties properties;
    private LawVectorClient client;
    private Responder responder;

    @BeforeEach
    void startHttpFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if ((exchange.getRequestMethod().equals("POST") || exchange.getRequestMethod().equals("PUT"))
                    && exchange.getRequestHeaders().getFirst("Content-Length") == null) {
                exchange.sendResponseHeaders(411, -1);
                exchange.close();
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    body.isEmpty() ? mapper.createObjectNode() : mapper.readTree(body), exchange.getRequestHeaders().getFirst("api-key"));
            synchronized (requests) { requests.add(request); }
            Response response = responder.respond(request);
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        properties = new LawVectorProperties();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        properties.setEmbeddingBaseUrl(base);
        properties.setQdrantBaseUrl(base);
        properties.setDimensions(2);
        properties.setApiKey("fixture-qdrant-key");
        client = new LawVectorClient(properties, mapper);
    }

    @AfterEach
    void stopHttpFixture() {
        server.stop(0);
    }

    @Test
    void readBackChecksExactCountIdsUnitAndSourceIdentityBeforePublication() {
        String id = "11111111-1111-1111-1111-111111111111";
        var expected = Map.<String, Object>of("generation", "test-generation", "content_hash", "expected-hash");
        responder = request -> request.path().endsWith("/count") ? json(Map.of("result", Map.of("count", 1)))
                : json(Map.of("result", List.of(Map.of("id", id, "vector", List.of(0.6, 0.8), "payload", Map.of(
                        "generation", "test-generation", "content_hash", "expected-hash", "unit_id", "123:1")))));
        assertTrue(client.verifyIndex(Map.of(id, "123:1"), expected, before::incrementAndGet, after::incrementAndGet));
        assertTrue(requests.get(0).body().path("exact").asBoolean());
        assertEquals("generation", requests.get(0).body().path("filter").path("must").get(0).path("key").asText());
        assertTrue(requests.get(1).body().path("with_vector").asBoolean());
        assertEquals(2, before.get());
    }

    @Test
    void readBackRejectsMissingCountOrWrongPayloadRatherThanTrustingSuccessfulWrites() {
        String id = "11111111-1111-1111-1111-111111111111";
        var expected = Map.<String, Object>of("generation", "expected");
        responder = request -> json(Map.of("result", Map.of("count", 0)));
        assertFalse(client.verifyIndex(Map.of(id, "123:1"), expected, before::incrementAndGet, after::incrementAndGet));
        assertEquals(1, requests.size());
        responder = request -> request.path().endsWith("/count") ? json(Map.of("result", Map.of("count", 1)))
                : json(Map.of("result", List.of(Map.of("id", id, "vector", List.of(0.6, 0.8), "payload", Map.of("generation", "different", "unit_id", "123:1")))));
        assertFalse(client.verifyIndex(Map.of(id, "123:1"), expected, before::incrementAndGet, after::incrementAndGet));
    }

    @Test
    void readBackRejectsDeletedOrWrongDimensionalVectorsEvenWhenPayloadAndCountMatch() {
        String id = "11111111-1111-1111-1111-111111111111";
        var expected = Map.<String, Object>of("generation", "expected");
        for (var vector : List.of(List.of(), List.of(1.0), List.of(0.0, 0.0))) {
            responder = request -> request.path().endsWith("/count") ? json(Map.of("result", Map.of("count", 1)))
                    : json(Map.of("result", List.of(Map.of("id", id, "vector", vector,
                            "payload", Map.of("generation", "expected", "unit_id", "123:1")))));
            assertFalse(client.verifyIndex(Map.of(id, "123:1"), expected, before::incrementAndGet, after::incrementAndGet));
        }
    }

    @Test
    void missingRetrievalCollectionNeverCreatesIt() {
        responder = request -> new Response(404, "{\"status\":{\"error\":\"Not found\"}}");
        assertFalse(client.collectionAvailable(before::incrementAndGet, after::incrementAndGet));
        assertEquals(1, requests.size());
        assertEquals("GET", requests.get(0).method());
    }

    @Test
    void sendsPinnedModelAndNonTruncatingCpuEmbeddingRequest() {
        responder = request -> request.path().equals("/api/tags") ? tags(properties.getRevision())
                : json(Map.of("model", properties.getModel(), "embeddings", List.of(List.of(0.6, 0.8))));

        assertEquals(List.of(List.of(0.6, 0.8)), client.embed(List.of("민법\n제1조"), before::incrementAndGet, after::incrementAndGet));

        assertEquals(2, before.get());
        assertEquals(2, after.get());
        assertEquals("GET", requests.get(0).method());
        Request embed = requests.get(1);
        assertEquals("POST", embed.method());
        assertEquals("/api/embed", embed.path());
        assertEquals(properties.getModel(), embed.body().path("model").asText());
        assertEquals("민법\n제1조", embed.body().path("input").get(0).asText());
        assertFalse(embed.body().path("truncate").asBoolean(true));
        assertEquals(0, embed.body().path("options").path("num_gpu").asInt(-1));
        assertEquals(6, embed.body().path("options").path("num_thread").asInt());
        assertNull(embed.apiKey(), "Qdrant credentials must not reach the embedding server");
    }

    @Test
    void expandedCandidateQueryKeepsPublicationAndExplicitUnitFilters() {
        responder = request -> json(Map.of("result", Map.of("groups", List.of())));
        client.query(List.of(0.6, 0.8), List.of("committed"), List.of("123:1"), 50,
                before::incrementAndGet, after::incrementAndGet);
        assertEquals(50, requests.get(0).body().path("limit").asInt());
        String filter = requests.get(0).body().path("filter").toString();
        assertTrue(filter.contains("committed"));
        assertTrue(filter.contains("config_fingerprint"));
        assertTrue(filter.contains("123:1"));
        assertThrows(LegalToolException.class, () -> client.query(List.of(0.6, 0.8), List.of("committed"),
                List.of(), 101, () -> {}, () -> {}));
    }

    @Test
    void reusesOnlyNormalizedVectorsWhoseSourceAndExactInputMatch() {
        String id = "11111111-1111-1111-1111-111111111111";
        String hash = com.MoleLaw_backend.law.util.LawEmbeddingInputHash.of("title", "text");
        var payload = new java.util.HashMap<String, Object>(Map.of("generation", "complete", "title", "title", "text", "text"));
        responder = request -> json(Map.of("result", List.of(Map.of("id", id, "payload", payload, "vector", List.of(.6, .8)))));
        assertEquals(Map.of(hash, List.of(.6, .8)), client.reusableVectors(Map.of(id, hash), Map.of("generation", "complete"), () -> {}, () -> {}));
        payload.put("text", "changed");
        assertTrue(client.reusableVectors(Map.of(id, hash), Map.of("generation", "complete"), () -> {}, () -> {}).isEmpty());
        payload.put("text", "text");
        payload.put("generation", "writing");
        assertTrue(client.reusableVectors(Map.of(id, hash), Map.of("generation", "complete"), () -> {}, () -> {}).isEmpty());
    }

    @Test
    void sendsConfiguredGpuAndThreadsWithoutChangingIndexIdentity() {
        String baselineFingerprint = client.fingerprint();
        properties.setGpuCount(1);
        properties.setThreads(4);
        client = new LawVectorClient(properties, mapper);
        responder = request -> request.path().equals("/api/tags") ? tags(properties.getRevision())
                : json(Map.of("model", properties.getModel(), "embeddings", List.of(List.of(0.6, 0.8))));
        client.embed(List.of("민법"), before::incrementAndGet, after::incrementAndGet);
        assertEquals(1, requests.get(1).body().path("options").path("num_gpu").asInt());
        assertEquals(4, requests.get(1).body().path("options").path("num_thread").asInt());
        assertEquals(baselineFingerprint, client.fingerprint());
    }

    @Test
    void rejectsUnsupportedGpuCountBeforeAnyHttpCall() {
        for (int count : List.of(-1, 2)) {
            properties.setGpuCount(count);
            assertThrows(IllegalArgumentException.class, () -> new LawVectorClient(properties, mapper));
        }
        assertTrue(requests.isEmpty());
    }

    @Test
    void indexingSessionChecksRevisionOnceAndRechecksAfterAFailedBatch() {
        responder = request -> request.path().equals("/api/tags") ? tags(properties.getRevision())
                : json(Map.of("model", properties.getModel(), "embeddings", List.of(List.of(0.6, 0.8))));
        var session = client.newEmbeddingSession();
        session.embed(List.of("1"), before::incrementAndGet, after::incrementAndGet);
        session.embed(List.of("2"), before::incrementAndGet, after::incrementAndGet);
        assertEquals(1, requests.stream().filter(r -> r.path().equals("/api/tags")).count());
        assertEquals(3, before.get());
        responder = request -> json(Map.of("model", properties.getModel(), "embeddings", List.of(List.of(1.0))));
        assertInvalid(() -> session.embed(List.of("bad"), before::incrementAndGet, after::incrementAndGet));
        responder = request -> tags("changed-revision");
        assertInvalid(() -> session.embed(List.of("retry"), before::incrementAndGet, after::incrementAndGet));
        assertEquals("/api/tags", requests.get(requests.size() - 1).path());
        assertInvalid(() -> client.newEmbeddingSession().embed(List.of("new-law"), before::incrementAndGet, after::incrementAndGet));
        assertEquals("/api/tags", requests.get(requests.size() - 1).path());
    }

    @Test
    void rejectsDifferentRevisionBeforeEmbedding() {
        responder = request -> tags("different-revision");
        assertInvalid(() -> client.embed(List.of("민법"), before::incrementAndGet, after::incrementAndGet));
        assertEquals(1, requests.size());
    }

    @Test
    void rejectsWrongDimensionsAndNonNumericOrUnnormalizedVectors() {
        for (String vector : List.of("[1]", "[\"NaN\",1]", "[1e999,0]", "[0,0]")) {
            responder = request -> request.path().equals("/api/tags") ? tags(properties.getRevision())
                    : new Response(200, "{\"model\":\"" + properties.getModel() + "\",\"embeddings\":[" + vector + "]}");
            assertInvalid(() -> client.embed(List.of("민법"), before::incrementAndGet, after::incrementAndGet));
        }
    }

    @Test
    void refusesCollectionSchemaMismatchWithoutChangingIt() {
        responder = request -> json(Map.of("result", Map.of("config", Map.of(
                "params", Map.of("vectors", Map.of("size", 3, "distance", "Cosine")), "metadata", client.identity()))));
        assertInvalid(() -> client.ensureCollection(before::incrementAndGet, after::incrementAndGet));
        assertEquals(1, requests.size());
        assertEquals("GET", requests.get(0).method());
    }

    @Test
    void createsOnlyMissingCollectionAndRechecksItsIdentity() {
        AtomicInteger gets = new AtomicInteger();
        responder = request -> {
            if (request.method().equals("GET") && gets.getAndIncrement() == 0) return new Response(404, "{\"status\":\"not found\"}");
            if (request.method().equals("PUT")) return json(Map.of("result", true));
            return collection();
        };
        client.ensureCollection(before::incrementAndGet, after::incrementAndGet);
        assertEquals(3, requests.size());
        assertEquals("PUT", requests.get(1).method());
        assertEquals(2, requests.get(1).body().path("vectors").path("size").asInt());
        assertEquals(mapper.valueToTree(client.identity()), requests.get(1).body().path("metadata"));
        assertEquals("fixture-qdrant-key", requests.get(1).apiKey());
        assertEquals(3, before.get());
        assertEquals(3, after.get());
    }

    @Test
    void waitsForUpsertCompletionAndRejectsAcknowledgedOnly() {
        responder = request -> json(Map.of("result", Map.of("status", "completed")));
        List<Map<String, Object>> points = List.of(Map.of("id", "b2f890e6-a75a-47ea-863d-92a21b088393", "vector", List.of(0.6, 0.8), "payload", Map.of("source_type", "LAW")));
        client.upsert(points, before::incrementAndGet, after::incrementAndGet);
        assertTrue(requests.get(0).path().endsWith("/points?wait=true"));
        assertEquals("PUT", requests.get(0).method());
        responder = request -> json(Map.of("result", Map.of("status", "acknowledged")));
        assertInvalid(() -> client.upsert(points, before::incrementAndGet, after::incrementAndGet));
    }

    @Test
    void requestsFifteenDistinctLawUnitsWithoutSimilarityCutoff() {
        responder = request -> json(Map.of("result", Map.of("groups", List.of())));
        assertTrue(client.query(List.of(0.6, 0.8), List.of("committed"), before::incrementAndGet, after::incrementAndGet).isEmpty());
        JsonNode body = requests.get(0).body();
        assertTrue(requests.get(0).path().endsWith("/points/query/groups"));
        assertEquals(15, body.path("limit").asInt());
        assertEquals(1, body.path("group_size").asInt());
        assertEquals("unit_id", body.path("group_by").asText());
        assertEquals("LAW", body.path("filter").path("must").get(0).path("match").path("value").asText());
        assertEquals("committed", body.path("filter").path("must").get(1).path("match").path("any").get(0).asText());
        assertEquals(client.fingerprint(), body.path("filter").path("must").get(2).path("match").path("value").asText());
        assertTrue(body.path("with_payload").asBoolean());
        assertFalse(body.path("with_vector").asBoolean(true));
        assertFalse(body.has("score_threshold"));
    }

    @Test
    void explicitArticleFilterKeepsGenerationAndFingerprintConstraints() {
        responder = request -> json(Map.of("result", Map.of("groups", List.of())));
        assertTrue(client.query(List.of(0.6, 0.8), List.of("committed"), List.of("123:575", "123:43-2"),
                before::incrementAndGet, after::incrementAndGet).isEmpty());
        var conditions = requests.get(0).body().path("filter").path("must");
        assertEquals(4, conditions.size());
        assertEquals("generation", conditions.get(1).path("key").asText());
        assertEquals("config_fingerprint", conditions.get(2).path("key").asText());
        assertEquals("unit_id", conditions.get(3).path("key").asText());
        assertEquals("123:575", conditions.get(3).path("match").path("any").get(0).asText());
    }

    @Test
    void mapsProviderErrorsWithoutLeakingBodyAndAccountsForFailedCalls() {
        responder = request -> new Response(503, "{\"secret\":\"provider-sensitive-body\"}");
        LegalToolException failure = assertThrows(LegalToolException.class,
                () -> client.query(List.of(0.6, 0.8), before::incrementAndGet, after::incrementAndGet));
        assertEquals(LegalToolException.Reason.API_FAILURE, failure.getReason());
        assertTrue(failure.isRetryable());
        assertFalse(failure.getMessage().contains("provider-sensitive-body"));
        assertEquals(1, before.get());
        assertEquals(1, after.get());
    }

    @Test
    void refusesHttpRequestWhenBudgetCallbackRejectsIt() {
        LegalToolException failure = assertThrows(LegalToolException.class,
                () -> client.query(List.of(0.6, 0.8), () -> { throw new LegalToolException(LegalToolException.Reason.BUDGET_EXHAUSTED, false); }, after::incrementAndGet));
        assertEquals(LegalToolException.Reason.BUDGET_EXHAUSTED, failure.getReason());
        assertTrue(requests.isEmpty());
        assertEquals(0, after.get());
    }

    private Response tags(String revision) {
        return json(Map.of("models", List.of(Map.of("name", properties.getModel(), "digest", revision))));
    }

    private Response collection() {
        return json(Map.of("result", Map.of("config", Map.of("params", Map.of("vectors", Map.of("size", 2, "distance", "Cosine")), "metadata", client.identity()))));
    }

    private Response json(Object body) {
        try { return new Response(200, mapper.writeValueAsString(body)); }
        catch (IOException failure) { throw new IllegalStateException(failure); }
    }

    private void assertInvalid(org.junit.jupiter.api.function.Executable call) {
        assertEquals(LegalToolException.Reason.INVALID_RESPONSE, assertThrows(LegalToolException.class, call).getReason());
    }

    private record Request(String method, String path, JsonNode body, String apiKey) { }
    private record Response(int status, String body) { }
    private interface Responder { Response respond(Request request); }
}
