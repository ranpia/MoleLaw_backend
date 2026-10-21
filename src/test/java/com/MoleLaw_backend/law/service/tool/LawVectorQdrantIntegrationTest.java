package com.MoleLaw_backend.law.service.tool;

import com.MoleLaw_backend.law.config.LawVectorProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Isolated real Qdrant validates collection metadata and grouped retrieval without a model. */
@Tag("qdrant-integration")
class LawVectorQdrantIntegrationTest {
    private static final Runnable NOOP = () -> { };

    @Test
    void isolatedCollectionPinsMetadataAndReturnsFifteenDistinctRankedArticles() {
        try (var database = new GenericContainer<>(DockerImageName.parse("qdrant/qdrant:v1.19.1"))
                .withExposedPorts(6333)
                .waitingFor(Wait.forHttp("/readyz").forPort(6333))
                .withStartupTimeout(Duration.ofMinutes(2))) {
            database.start();
            var properties = new LawVectorProperties();
            properties.setQdrantBaseUrl("http://" + database.getHost() + ":" + database.getMappedPort(6333));
            properties.setCollectionName("molelaw_service_isolated_vector_test");
            properties.setModel("synthetic-unit-test");
            properties.setRevision("synthetic-unit-test-v1");
            properties.setDimensions(2);
            var mapper = new ObjectMapper();
            var client = new LawVectorClient(properties, mapper);
            client.ensureCollection(NOOP, NOOP);
            // A repeat verifies the existing collection rather than creating or altering it.
            client.ensureCollection(NOOP, NOOP);
            List<Map<String, Object>> points = new ArrayList<>();
            for (int article = 0; article < 20; article++) {
                for (int chunk = 0; chunk < 2; chunk++) {
                    double angle = 0.02 * (article + 1) + 0.005 * chunk;
                    String key = article + ":" + chunk;
                    points.add(Map.of("id", UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString(),
                            "vector", List.of(Math.cos(angle), Math.sin(angle)),
                            "payload", Map.of("source_type", "LAW", "unit_id", "article-" + article,
                                    "chunk_index", chunk, "text", "Synthetic article " + article)));
                }
            }
            // A closer non-law point must never enter the legal retrieval result.
            points.add(Map.of("id", UUID.randomUUID().toString(), "vector", List.of(1.0, 0.0),
                    "payload", Map.of("source_type", "CASE", "unit_id", "excluded-case")));
            client.upsert(points, NOOP, NOOP);
            JsonNode groups = client.query(List.of(1.0, 0.0), NOOP, NOOP);
            assertThat(groups).hasSize(15);
            var uniqueArticles = new HashSet<String>();
            double previous = Double.POSITIVE_INFINITY;
            for (int rank = 0; rank < groups.size(); rank++) {
                JsonNode group = groups.get(rank);
                assertThat(uniqueArticles.add(group.path("id").asText())).isTrue();
                assertThat(group.path("id").asText()).isEqualTo("article-" + rank);
                assertThat(group.path("hits")).hasSize(1);
                JsonNode hit = group.path("hits").get(0);
                assertThat(hit.path("payload").path("source_type").asText()).isEqualTo("LAW");
                assertThat(hit.path("payload").path("chunk_index").asInt()).isZero();
                double score = hit.path("score").asDouble();
                assertThat(score).isFinite().isLessThanOrEqualTo(previous);
                previous = score;
            }
            properties.setRevision("different-revision");
            var incompatible = new LawVectorClient(properties, mapper);
            assertThatThrownBy(() -> incompatible.ensureCollection(NOOP, NOOP))
                    .isInstanceOfSatisfying(LegalToolException.class,
                            failure -> assertThat(failure.getReason()).isEqualTo(LegalToolException.Reason.INVALID_RESPONSE));
        }
    }
}
