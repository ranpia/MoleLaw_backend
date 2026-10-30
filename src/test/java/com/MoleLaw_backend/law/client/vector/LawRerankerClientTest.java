package com.MoleLaw_backend.law.client.vector;

import com.MoleLaw_backend.law.config.LawRerankerProperties;
import com.MoleLaw_backend.law.dto.vector.LawSearchEvidence;
import com.MoleLaw_backend.law.exception.LegalToolException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class LawRerankerClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LawRerankerProperties properties = new LawRerankerProperties();
    private final AtomicInteger after = new AtomicInteger();
    private HttpServer server;
    private LawRerankerClient client;
    private ObjectNode response;
    private com.fasterxml.jackson.databind.JsonNode request;
    private int status = 200;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/rerank", exchange -> {
            request = mapper.readTree(exchange.getRequestBody());
            byte[] bytes = mapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setEnabled(true);
        client = new LawRerankerClient(properties, mapper);
        response = mapper.createObjectNode().put("model", properties.getModel()).put("revision", properties.getRevision());
    }

    @AfterEach void stop() { server.stop(0); }

    private List<LawSearchEvidence> candidates(int count) {
        return IntStream.range(0, count).mapToObj(i -> new LawSearchEvidence("123:" + i, "source", "123",
                "law", "20260101", "title", "matching chunk", "full article", "https://www.law.go.kr", .9 - i * .001,
                "embedding-revision", "fingerprint", "committed-generation")).toList();
    }

    private void scores(int count) {
        var results = response.putArray("results");
        for (int i = 0; i < count; i++) results.addObject().put("id", "123:" + i).put("score", i - 10.0);
    }

    @Test void reranksExpandedCandidatesWithoutReplacingEvidenceOrDenseScores() {
        var candidates = candidates(20);
        scores(20);
        var result = client.rerank("question", candidates, () -> {}, after::incrementAndGet);
        assertEquals(15, result.size());
        assertSame(candidates.get(19), result.get(0));
        assertSame(candidates.get(5), result.get(14));
        assertEquals(1, after.get());
        assertEquals(20, request.path("documents").size());
        assertEquals("title\nmatching chunk", request.path("documents").get(0).path("text").asText());
        assertThrows(UnsupportedOperationException.class, result::clear);
    }

    @Test void rejectsMissingDuplicateUnknownNonNumericScoresAndWrongRevision() {
        for (int kind = 0; kind < 5; kind++) {
            response.put("revision", properties.getRevision());
            scores(2);
            if (kind == 0) response.withArray("results").remove(1);
            if (kind == 1) ((ObjectNode) response.path("results").get(1)).put("id", "123:0");
            if (kind == 2) ((ObjectNode) response.path("results").get(1)).put("id", "unknown");
            if (kind == 3) ((ObjectNode) response.path("results").get(1)).put("score", "NaN");
            if (kind == 4) response.put("revision", "different");
            var error = assertThrows(LegalToolException.class,
                    () -> client.rerank("question", candidates(2), () -> {}, () -> {}));
            assertEquals(LegalToolException.Reason.INVALID_RESPONSE, error.getReason());
        }
    }

    @Test void rejectsLateResponseInsteadOfReturningRankingsAfterDeadline() {
        scores(1);
        var error = assertThrows(LegalToolException.class, () -> client.rerank("question", candidates(1),
                () -> {}, () -> { throw new LegalToolException(LegalToolException.Reason.BUDGET_EXHAUSTED, false); }));
        assertEquals(LegalToolException.Reason.BUDGET_EXHAUSTED, error.getReason());
    }

    @Test void mapsServerFailureWithoutDenseFallbackOrProviderErrorLeak() {
        status = 503;
        response.put("error", "private-provider-content");
        var error = assertThrows(LegalToolException.class,
                () -> client.rerank("question", candidates(1), () -> {}, after::incrementAndGet));
        assertEquals(LegalToolException.Reason.API_FAILURE, error.getReason());
        assertFalse(error.getMessage().contains("private-provider-content"));
        assertEquals(1, after.get());
    }
}
