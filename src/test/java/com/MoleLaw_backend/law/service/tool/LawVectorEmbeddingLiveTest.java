package com.MoleLaw_backend.law.service.tool;
import com.MoleLaw_backend.law.dto.vector.LawSearchEvidence;
import com.MoleLaw_backend.law.dto.tool.LegalToolContracts;
import com.MoleLaw_backend.law.service.source.LegalSourceService;
import com.MoleLaw_backend.law.client.vector.LawVectorClient;

import com.MoleLaw_backend.law.config.LawVectorProperties;
import com.MoleLaw_backend.law.entity.LegalSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real CPU inference and real Qdrant. Controlled fixtures are not an accuracy benchmark or actual statutes. */
@Tag("embedding-live")
class LawVectorEmbeddingLiveTest {
    @Test
    void realModelIndexesAndRetrievesFifteenDistinctArticles() throws Exception {
        Instant started = Instant.now();
        try (var database = new GenericContainer<>(DockerImageName.parse("qdrant/qdrant:v1.19.1"))
                .withExposedPorts(6333).waitingFor(Wait.forHttp("/readyz").forPort(6333))) {
            database.start();
            ObjectMapper mapper = new ObjectMapper();
            var properties = new LawVectorProperties();
            properties.setEmbeddingBaseUrl(System.getenv().getOrDefault("LAW_EMBEDDING_LIVE_URL", "http://127.0.0.1:11436"));
            properties.setQdrantBaseUrl("http://" + database.getHost() + ":" + database.getMappedPort(6333));
            properties.setCollectionName("molelaw_service_live_verification");
            // Force multiple chunks per article to exercise real grouped retrieval.
            properties.setChunkSize(45);
            properties.setChunkOverlap(10);
            List<Map<String, String>> articles = new ArrayList<>();
            for (int i = 1; i <= 20; i++) {
                articles.add(Map.of("조문번호", Integer.toString(i), "조문제목", "검증용 조문 " + i,
                        "조문내용", "이 문장은 실제 법령이 아닌 검색 검증용 자료입니다. 계약의 이행과 손해배상 책임을 검토하며 당사자의 권리와 의무를 확인합니다. 검증 번호 " + i));
            }
            String raw = mapper.writeValueAsString(Map.of("조문", Map.of("조문단위", articles)));
            var source = new LegalSource("LAW", "999999", "999999", "20261021", "https://example.invalid/test-fixture",
                    "live-fixture-v1", raw);
            var sources = mock(LegalSourceService.class); // Only source persistence is replaced; both external services are real.
            when(sources.restore(source.getId())).thenReturn(source);
            var context = new LegalToolContext(LegalToolContext.Purpose.PRECOLLECTION, "live-verification",
                    Instant.now().plusSeconds(900), 100, Clock.systemUTC());
            context.details.put("LAW:999999", new LegalToolContracts.DetailResult(source.getId(), "999999", "999999",
                    "20261021", "검증용 법령 자료", 20, true, source.getCollectedAt(), source.getSourceUrl(), List.of()));
            var client = new LawVectorClient(properties, mapper);
            var indexes = InMemoryIndexes.create(sources);
            var writer = new LawVectorizationToolService(client, properties, mapper, sources, indexes);
            var reader = new LawRagSearchToolService(client, indexes);
            var indexed = writer.vectorizeLaw(context, source.getId());
            assertThat(indexed.articleCount()).isEqualTo(20);
            assertThat(indexed.chunkCount()).isGreaterThan(20);
            var hits = reader.searchTop15(context, "계약을 이행하지 않은 경우 손해배상 책임과 당사자의 의무");
            assertThat(hits).hasSize(15);
            assertThat(hits).extracting(LawSearchEvidence::unitId).doesNotHaveDuplicates();
            assertThat(hits).allSatisfy(hit -> {
                assertThat(hit.referenceId()).isEqualTo(source.getId());
                assertThat(hit.sourceUrl()).isEqualTo(source.getSourceUrl());
                assertThat(hit.score()).isFinite().isBetween(-1.0001, 1.0001);
                assertThat(hit.text()).isNotBlank();
            });
            for (int i = 1; i < hits.size(); i++) assertThat(hits.get(i).score()).isLessThanOrEqualTo(hits.get(i - 1).score());
            System.out.printf("LIVE_EMBEDDING model=%s dimensions=%d articles=%d chunks=%d hits=%d calls=%d elapsedSeconds=%d%n",
                    properties.getModel(), properties.getDimensions(), indexed.articleCount(), indexed.chunkCount(),
                    hits.size(), context.callsUsed(), Duration.between(started, Instant.now()).toSeconds());
        }
    }
}
