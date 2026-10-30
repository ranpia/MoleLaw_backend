package com.MoleLaw_backend.law.service.tool;
import com.MoleLaw_backend.law.client.vector.LawVectorClient;

import com.MoleLaw_backend.law.config.LawVectorProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Opt-in: actual HTTP against the local Postman embedding simulation, no live model. */
@EnabledIfEnvironmentVariable(named = "LAW_VECTOR_SIMULATION_URL", matches = "http://127\\.0\\.0\\.1:[0-9]+")
@Tag("postman-simulation")
class LawVectorPostmanSimulationTest {
    @Test
    void readerTransformerWriterAndRetrievalRunAgainstHttpEmbeddingAndIsolatedQdrant() {
        try (var qdrant = new org.testcontainers.containers.GenericContainer<>(
                org.testcontainers.utility.DockerImageName.parse("qdrant/qdrant:v1.19.1"))
                .withExposedPorts(6333)
                .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/readyz").forPort(6333))) {
            qdrant.start();
            var properties = new LawVectorProperties();
            properties.setEmbeddingBaseUrl(System.getenv("LAW_VECTOR_SIMULATION_URL"));
            properties.setDimensions(2);
            properties.setBatchSize(1);
            properties.setChunkSize(24);
            properties.setChunkOverlap(4);
            properties.setQdrantBaseUrl("http://" + qdrant.getHost() + ":" + qdrant.getMappedPort(6333));
            properties.setCollectionName("molelaw_service_etl_simulation");
            var mapper = new ObjectMapper();
            var source = new com.MoleLaw_backend.law.entity.LegalSource("LAW", "123", "1", "20260101",
                    "https://www.law.go.kr/법령?lsId=1&lsiSeq=123", "a".repeat(64),
                    "{\"기본정보\":{\"법령명_한글\":\"검증용 법령\"},\"조문\":{\"조문단위\":{\"조문번호\":\"1\",\"조문내용\":\""
                            + "이 문장은 ETL 검증용 가상 조문입니다. ".repeat(160) + "\"}}}");
            var sources = org.mockito.Mockito.mock(com.MoleLaw_backend.law.service.source.LegalSourceService.class);
            org.mockito.Mockito.when(sources.restore(source.getId())).thenReturn(source);
            var client = new LawVectorClient(properties, mapper);
            var indexes = InMemoryIndexes.create(sources);
            var writer = new LawVectorizationToolService(client, properties, mapper, sources, indexes);
            var reader = new LawRagSearchToolService(client, indexes, sources, mapper);
            var clock = java.time.Clock.systemUTC();
            var context = new LegalToolContext(LegalToolContext.Purpose.PRECOLLECTION, "etl-simulation",
                    clock.instant().plusSeconds(120), 4, clock);
            var result = writer.reindexStoredLaw(context, source.getId());
            org.junit.jupiter.api.Assertions.assertTrue(result.chunkCount() > 128);
            assertEquals(1, result.articleCount());
            var generation = indexes.searchable(client.fingerprint()).keySet();
            writer.reindexStoredLaw(context, source.getId());
            assertEquals(generation, indexes.searchable(client.fingerprint()).keySet());
            var hits = reader.searchTop15(context, "검증용 조문");
            assertEquals(1, hits.size());
            assertEquals(source.getId(), hits.get(0).referenceId());
            assertEquals("123:1", hits.get(0).unitId());
            var exactContext = new LegalToolContext(LegalToolContext.Purpose.PRECOLLECTION, "exact-article-simulation",
                    clock.instant().plusSeconds(120), 4, clock);
            assertEquals("123:1", reader.searchTop15(exactContext, "검증용 법령 제1조").get(0).unitId());
            assertEquals(0, reader.searchTop15(exactContext, "검증용 법령 제999조").size());
            // Remove a point behind a COMPLETE DB record: read-back must reject the stale cache
            // and reindex the saved source with a new generation before retrieval resumes.
            var http = org.springframework.web.client.RestClient.create(properties.getQdrantBaseUrl());
            http.post().uri("/collections/" + properties.getCollectionName() + "/points/delete?wait=true")
                    .body(Map.of("filter", Map.of("must", List.of(Map.of("key", "generation", "match",
                            Map.of("any", List.copyOf(generation))))))).retrieve().toBodilessEntity();
            writer.reindexStoredLaw(context, source.getId());
            var repaired = indexes.searchable(client.fingerprint()).keySet();
            org.junit.jupiter.api.Assertions.assertNotEquals(generation, repaired);
            assertEquals(1, reader.searchTop15(context, "검증용 조문").size());
            assertEquals(4, context.callBudgets().get("retrievalUsed"));
            org.junit.jupiter.api.Assertions.assertTrue((int) context.callBudgets().get("embeddingUsed") > 128);
            org.junit.jupiter.api.Assertions.assertTrue((int) context.callBudgets().get("indexUsed") > 128);
        }
    }

    @Test
    void realClientUsesSimulatedEmbeddingContract() {
        LawVectorProperties properties = new LawVectorProperties();
        properties.setEmbeddingBaseUrl(System.getenv("LAW_VECTOR_SIMULATION_URL"));
        properties.setDimensions(2);
        LawVectorClient client = new LawVectorClient(properties, new ObjectMapper());
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        assertEquals(List.of(List.of(0.6, 0.8)), client.embed(List.of("민법\n제1조 법원"),
                calls::incrementAndGet, completed::incrementAndGet));
        assertEquals(2, calls.get());
        assertEquals(2, completed.get());
    }
}
