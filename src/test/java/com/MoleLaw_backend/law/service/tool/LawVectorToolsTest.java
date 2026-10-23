package com.MoleLaw_backend.law.service.tool;
import com.MoleLaw_backend.law.dto.tool.LegalToolContracts;
import com.MoleLaw_backend.law.service.source.LegalSourceIndexService;
import com.MoleLaw_backend.law.service.source.LegalSourceService;
import com.MoleLaw_backend.law.client.vector.LawVectorClient;
import com.MoleLaw_backend.law.exception.LegalToolException;

import com.MoleLaw_backend.law.config.LawVectorProperties;
import com.MoleLaw_backend.law.entity.LegalSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LawVectorToolsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LawVectorClient client = mock(LawVectorClient.class);
    private final LegalSourceService sources = mock(LegalSourceService.class);
    private final LawVectorProperties properties = new LawVectorProperties();
    private final LegalSourceIndexService indexes = InMemoryIndexes.create(sources);
    private final LawVectorizationToolService writer = new LawVectorizationToolService(client, properties, mapper, sources, indexes);
    private final LawRagSearchToolService reader = new LawRagSearchToolService(client, indexes, sources, mapper);

    @BeforeEach
    void configure() {
        when(client.embeddingCallLimit()).thenReturn(properties.getEmbeddingMaxCalls());
        when(client.indexingCallLimit()).thenReturn(properties.getIndexingMaxCalls());
        var session = mock(LawVectorClient.EmbeddingSession.class);
        when(client.newEmbeddingSession()).thenReturn(session);
        when(session.embed(anyList(), any(), any())).thenAnswer(call ->
                client.embed(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        when(client.identity()).thenReturn(Map.of("model", "model", "revision", "revision", "dimensions", 1));
        when(client.fingerprint()).thenReturn("f".repeat(64));
        when(client.collectionName()).thenReturn(properties.getCollectionName());
        when(client.embeddingTimeout()).thenReturn(java.time.Duration.ofSeconds(properties.getEmbeddingTimeoutSeconds()));
        when(client.collectionAvailable(any(), any())).thenReturn(true);
        when(client.verifyIndex(anyMap(), anyMap(), any(), any())).thenReturn(true);
        when(client.embed(anyList(), any(), any())).thenReturn(List.of(List.of(1.0)));
    }

    @Test
    void failedReadBackCannotPublishAnIndexAndNextInvocationRebuildsIt() {
        var context = context(false);
        var source = source(context);
        when(client.verifyIndex(anyMap(), anyMap(), any(), any())).thenReturn(false, true);
        assertThatThrownBy(() -> writer.vectorizeLaw(context, source.getId())).hasMessage("INDEX_VERIFICATION_FAILED");
        assertThat(indexes.searchable(client.fingerprint())).isEmpty();
        writer.vectorizeLaw(context, source.getId());
        assertThat(indexes.searchable(client.fingerprint())).hasSize(1);
        verify(client, times(2)).embed(anyList(), any(), any());
    }

    @Test
    void cachedIndexWithMissingPointsIsRebuiltAsANewGeneration() {
        var context = context(false);
        var source = source(context);
        writer.vectorizeLaw(context, source.getId());
        var first = indexes.searchable(client.fingerprint());
        when(client.verifyIndex(anyMap(), anyMap(), any(), any())).thenReturn(false, true);
        writer.vectorizeLaw(context, source.getId());
        var replacement = indexes.searchable(client.fingerprint());
        assertThat(replacement).hasSize(1);
        assertThat(replacement.keySet()).doesNotContainAnyElementsOf(first.keySet());
        verify(client, times(2)).embed(anyList(), any(), any());
    }

    @Test
    void cachedIndexReadBackOutageNeverTriggersReembeddingOrInvalidatesPriorGeneration() {
        var context = context(false);
        var source = source(context);
        writer.vectorizeLaw(context, source.getId());
        var before = indexes.searchable(client.fingerprint());
        when(client.verifyIndex(anyMap(), anyMap(), any(), any()))
                .thenThrow(new LegalToolException(LegalToolException.Reason.API_FAILURE, true));
        assertThatThrownBy(() -> writer.vectorizeLaw(context, source.getId())).hasMessage("API_FAILURE");
        assertThat(indexes.searchable(client.fingerprint())).isEqualTo(before);
        verify(client, times(1)).embed(anyList(), any(), any());
    }

    @Test
    void failedSavedSourceCanBeRetriedWithoutApiAndCompletedRetryIsReused() {
        var source = new LegalSource("LAW", "123", "1", "20260101", "https://www.law.go.kr/법령", "c".repeat(64),
                "{\"기본정보\":{\"법령명_한글\":\"민법\"},\"조문\":{\"조문단위\":{\"조문번호\":\"1\",\"조문내용\":\"신의성실\"}}}");
        when(sources.restore(source.getId())).thenReturn(source);
        doThrow(new LegalToolException(LegalToolException.Reason.API_FAILURE, true))
                .doNothing().when(client).upsert(anyList(), any(), any());
        assertThatThrownBy(() -> writer.reindexStoredLaw(context(false), source.getId())).hasMessage("API_FAILURE");
        assertThat(indexes.searchable(client.fingerprint())).isEmpty();
        writer.reindexStoredLaw(context(false), source.getId());
        var completed = indexes.searchable(client.fingerprint());
        assertThat(completed).hasSize(1);
        writer.reindexStoredLaw(context(false), source.getId());
        assertThat(indexes.searchable(client.fingerprint())).isEqualTo(completed);
        verify(client, times(2)).embed(anyList(), any(), any());
        assertThatThrownBy(() -> writer.reindexStoredLaw(context(true), source.getId())).hasMessage("FORBIDDEN");
    }

    @Test
    void lateEmbeddingIsFailedBeforeAnyQdrantWriteOrPublication() {
        var clock = new LegalToolContextEmbeddingTimeTest.MutableClock();
        var context = new LegalToolContext(LegalToolContext.Purpose.PRECOLLECTION, "embedding-timeout",
                clock.instant().plusSeconds(60), 128, clock);
        var source = source(context);
        properties.setEmbeddingTimeoutSeconds(1);
        when(client.embed(anyList(), any(), any())).thenAnswer(call -> {
            clock.advance(2);
            return List.of(List.of(1.0));
        });
        assertThatThrownBy(() -> writer.vectorizeLaw(context, source.getId())).hasMessage("EMBEDDING_TIMEOUT");
        verify(client, never()).upsert(anyList(), any(), any());
        assertThat(indexes.searchable(client.fingerprint())).isEmpty();
        context.checkDeadline();
    }

    @Test
    void unknownSourceAndFollowUpNeverReachProviders() {
        assertThatThrownBy(() -> writer.vectorizeLaw(context(false), "unknown")).hasMessage("UNKNOWN_ID");
        assertThatThrownBy(() -> writer.vectorizeLaw(context(true), "unknown")).hasMessage("FORBIDDEN");
        assertThatThrownBy(() -> reader.searchTop15(context(true), "민법")).hasMessage("FORBIDDEN");
        verify(client, never()).embed(anyList(), any(), any());
        verifyNoInteractions(sources);
    }

    @Test
    void unchangedSourceReusesCompletedGenerationWithoutReembedding() {
        var context = context(false);
        var source = source(context);
        assertThat(writer.vectorizeLaw(context, source.getId()).articleCount()).isEqualTo(1);
        String first = indexes.searchable(client.fingerprint()).keySet().iterator().next();
        writer.vectorizeLaw(context, source.getId());
        String second = indexes.searchable(client.fingerprint()).keySet().iterator().next();
        assertThat(first).isEqualTo(second);
        verify(client, times(1)).embed(anyList(), any(), any());
        assertThat(indexes.searchable("different-configuration")).isEmpty();
    }

    @Test
    void partialQdrantFailureCannotPublishAndPreviousGenerationRemainsSearchable() {
        var context = context(false);
        var source = source(context);
        writer.vectorizeLaw(context, source.getId());
        var before = indexes.searchable(client.fingerprint());
        var changed = changedSource(context);
        properties.setBatchSize(1);
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            if (writes.incrementAndGet() == 2) throw new LegalToolException(LegalToolException.Reason.API_FAILURE, true);
            return null;
        }).when(client).upsert(anyList(), any(), any());
        assertThatThrownBy(() -> writer.vectorizeLaw(context, changed.getId())).hasMessage("API_FAILURE");
        assertThat(writes.get()).isEqualTo(2);
        assertThat(indexes.searchable(client.fingerprint())).isEqualTo(before);
    }

    @Test
    void embeddingFailureNeverWritesOrPublishes() {
        var context = context(false);
        var source = source(context);
        when(client.embed(anyList(), any(), any())).thenThrow(new LegalToolException(LegalToolException.Reason.API_FAILURE, true));
        assertThatThrownBy(() -> writer.vectorizeLaw(context, source.getId())).hasMessage("API_FAILURE");
        verify(client, never()).upsert(anyList(), any(), any());
        assertThat(indexes.searchable(client.fingerprint())).isEmpty();
    }

    @Test
    void retrievalChecksCommittedGenerationAndReturnsImmutableArticles() {
        var context = context(false);
        var source = source(context);
        writer.vectorizeLaw(context, source.getId());
        var index = indexes.searchable(client.fingerprint()).values().iterator().next();
        when(client.collectionAvailable(any(), any())).thenReturn(true);
        var payload = new HashMap<String, Object>();
        payload.putAll(Map.of("unit_id", "123:1", "source_type", "LAW", "reference_id", source.getId(),
                "external_id", "123", "title", "민법 제1조", "text", "본문", "article_text", "전체 본문",
                "source_url", source.getSourceUrl()));
        payload.put("law_id", source.getLawId());
        payload.put("effective_date", source.getEffectiveDate());
        payload.putAll(Map.of("generation", index.getGeneration(), "config_fingerprint", index.getConfigFingerprint(),
                "model_revision", index.getModelRevision(), "content_hash", source.getContentHash()));
        var group = Map.of("id", "123:1", "hits", List.of(Map.of("score", 0.8, "payload", payload)));
        when(client.query(anyList(), anyList(), any(), any())).thenReturn(mapper.valueToTree(List.of(group)));
        var result = reader.searchTop15(context, "권리의 행사");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).generation()).isEqualTo(index.getGeneration());
        assertThatThrownBy(result::clear).isInstanceOf(UnsupportedOperationException.class);
        verify(client).query(anyList(), eq(List.of(index.getGeneration())), any(), any());
        payload.put("generation", UUID.randomUUID().toString());
        when(client.query(anyList(), anyList(), any(), any())).thenReturn(mapper.valueToTree(List.of(group)));
        assertThatThrownBy(() -> reader.searchTop15(context, "권리의 행사")).hasMessage("INVALID_RESPONSE");
    }

    @Test
    void missingCollectionOrNoPublishedGenerationReturnsEmptyWithoutEmbedding() {
        when(client.collectionAvailable(any(), any())).thenReturn(false);
        assertThat(reader.searchTop15(context(false), "민법")).isEmpty();
        when(client.collectionAvailable(any(), any())).thenReturn(true);
        assertThat(reader.searchTop15(context(false), "민법")).isEmpty();
        verify(client, never()).ensureCollection(any(), any());
        verify(client, never()).embed(anyList(), any(), any());
    }

    private LegalSource source(LegalToolContext context) {
        var source = new LegalSource("LAW", "123", "1", "20260101", "https://www.law.go.kr/법령", "a".repeat(64),
                "{\"조문\":{\"조문단위\":{\"조문번호\":\"1\",\"조문내용\":\"권리는 신의에 따라 행사한다.\"}}}");
        context.details.put("LAW:123", new LegalToolContracts.DetailResult(source.getId(), "123", "1", "20260101", "민법", 1, true,
                Instant.now(), source.getSourceUrl(), List.of("1")));
        when(sources.restore(source.getId())).thenReturn(source);
        return source;
    }

    private LegalToolContext context(boolean followUp) {
        return new LegalToolContext(followUp ? LegalToolContext.Purpose.FOLLOW_UP : LegalToolContext.Purpose.FIRST_QUESTION,
                "execution", Instant.now().plusSeconds(300), 100, Clock.systemUTC());
    }

    private LegalSource changedSource(LegalToolContext context) {
        var source = new LegalSource("LAW", "123", "1", "20260101", "https://www.law.go.kr/법령", "b".repeat(64),
                "{\"조문\":{\"조문단위\":[{\"조문번호\":\"1\",\"조문내용\":\"변경된 조문입니다.\"},"
                        + "{\"조문번호\":\"2\",\"조문내용\":\"추가된 두 번째 조문입니다.\"}]}}");
        context.details.put("LAW:123", new LegalToolContracts.DetailResult(source.getId(), "123", "1", "20260101", "민법", 1, true,
                Instant.now(), source.getSourceUrl(), List.of("1")));
        when(sources.restore(source.getId())).thenReturn(source);
        return source;
    }
}
