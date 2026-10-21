package com.MoleLaw_backend.law.service.tool;

import com.MoleLaw_backend.law.config.LawVectorProperties;
import com.MoleLaw_backend.law.entity.LegalSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LawVectorToolsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LawVectorClient client = mock(LawVectorClient.class);
    private final LegalSourceService sources = mock(LegalSourceService.class);
    private final LawVectorProperties properties = new LawVectorProperties();
    private final LawVectorizationToolService writer = new LawVectorizationToolService(client, properties, mapper, sources);
    private final LawRagSearchToolService reader = new LawRagSearchToolService(client);

    @Test
    void unknownSourceAndFollowUpNeverReachStorageOrProviders() {
        assertThatThrownBy(() -> writer.vectorizeLaw(context(false), "unknown"))
                .isInstanceOf(LegalToolException.class).hasMessage("UNKNOWN_ID");
        assertThatThrownBy(() -> writer.vectorizeLaw(context(true), "unknown")).hasMessage("FORBIDDEN");
        assertThatThrownBy(() -> reader.searchTop15(context(true), "민법")).hasMessage("FORBIDDEN");
        verifyNoInteractions(client, sources);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void sameSnapshotUsesStablePointIdsAndEmbeddingFailureNeverWrites() {
        var source = new LegalSource("LAW", "123", "1", "20260101", "https://www.law.go.kr/법령", "hash",
                "{\"조문\":{\"조문단위\":{\"조문번호\":\"1\",\"조문내용\":\"권리는 성실하게 행사한다.\"}}}");
        var context = context(false);
        context.details.put("LAW:123", new LegalToolContracts.DetailResult(source.getId(), "123", "1", "20260101",
                "민법", 1, true, Instant.now(), source.getSourceUrl(), List.of("1")));
        when(sources.restore(source.getId())).thenReturn(source);
        when(client.embed(anyList(), any(), any())).thenReturn(List.of(List.of(1.0)));
        assertThat(writer.vectorizeLaw(context, source.getId()).articleCount()).isEqualTo(1);
        writer.vectorizeLaw(context, source.getId());
        ArgumentCaptor<List<Map<String, Object>>> points = ArgumentCaptor.forClass((Class) List.class);
        verify(client, times(2)).upsert(points.capture(), any(), any());
        assertThat(points.getAllValues().get(0)).isEqualTo(points.getAllValues().get(1));
        assertThat(((Map<String, Object>) points.getValue().get(0).get("payload")))
                .containsEntry("reference_id", source.getId()).containsEntry("source_type", "LAW");
        clearInvocations(client);
        when(client.embed(anyList(), any(), any())).thenThrow(new LegalToolException(LegalToolException.Reason.API_FAILURE, true));
        assertThatThrownBy(() -> writer.vectorizeLaw(context, source.getId())).hasMessage("API_FAILURE");
        verify(client, never()).upsert(any(), any(), any());
    }

    @Test
    void retrievalReturnsImmutableRankedEvidenceAndRejectsDuplicateArticles() throws Exception {
        when(client.collectionAvailable(any(), any())).thenReturn(true);
        when(client.embed(anyList(), any(), any())).thenReturn(List.of(List.of(1.0)));
        var payload = Map.of("unit_id", "123:1", "source_type", "LAW", "reference_id", "ref",
                "external_id", "123", "title", "민법 제1조", "text", "본문", "article_text", "전체 본문",
                "source_url", "https://www.law.go.kr/법령");
        var group = Map.of("id", "123:1", "hits", List.of(Map.of("score", 0.8, "payload", payload)));
        when(client.query(anyList(), any(), any())).thenReturn(mapper.valueToTree(List.of(group)));
        var result = reader.searchTop15(context(false), "신의 성실");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).score()).isEqualTo(0.8);
        assertThatThrownBy(() -> result.clear()).isInstanceOf(UnsupportedOperationException.class);
        when(client.query(anyList(), any(), any())).thenReturn(mapper.valueToTree(List.of(group, group)));
        assertThatThrownBy(() -> reader.searchTop15(context(false), "신의 성실")).hasMessage("INVALID_RESPONSE");
    }

    @Test
    void missingCollectionReturnsEmptyWithoutEmbeddingOrCreatingStorage() {
        assertThat(reader.searchTop15(context(false), "민법")).isEmpty();
        verify(client, never()).ensureCollection(any(), any());
        verify(client, never()).embed(anyList(), any(), any());
    }

    private LegalToolContext context(boolean followUp) {
        return new LegalToolContext(followUp ? LegalToolContext.Purpose.FOLLOW_UP : LegalToolContext.Purpose.FIRST_QUESTION,
                "execution", Instant.now().plusSeconds(300), 100, Clock.systemUTC());
    }
}
