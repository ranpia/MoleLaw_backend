package com.MoleLaw_backend.law.service.retrieval;
import com.MoleLaw_backend.law.dto.vector.LawSearchEvidence;
import com.MoleLaw_backend.law.dto.tool.LegalToolContracts;
import com.MoleLaw_backend.law.exception.LegalToolException;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.law.dto.retrieval.EvidenceDecision;
import com.MoleLaw_backend.law.service.retrieval.EvidenceDecisionPlanner;
import com.MoleLaw_backend.law.service.tool.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegalEvidenceCollectorTest {
    private final LawRagSearchToolService rag = mock(LawRagSearchToolService.class);
    private final LawVectorizationToolService vectors = mock(LawVectorizationToolService.class);
    private final LegalApiToolService laws = mock(LegalApiToolService.class);
    private final EvidenceDecisionPlanner planner = mock(EvidenceDecisionPlanner.class);
    private final LegalEvidenceCollector collector = new LegalEvidenceCollector(provider(rag), provider(vectors), laws, planner);

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
    private LawSearchEvidence hit() {
        return new LawSearchEvidence("123:1", UUID.randomUUID().toString(), "123", "001823",
                "20260101", "민법 제1조", "조문", "민사에 관하여 적용한다.", "https://www.law.go.kr/법령?lsiSeq=123", .9,
                "revision", "a".repeat(64), UUID.randomUUID().toString());
    }
    private EvidenceDecision ready(List<EvidenceItem> evidence) {
        return new EvidenceDecision(EvidenceDecision.Action.ANSWER_READY, "근거 확보", List.of(),
                evidence.stream().map(EvidenceItem::id).toList(), List.of(), List.of());
    }
    private EvidenceDecision collect() {
        return new EvidenceDecision(EvidenceDecision.Action.COLLECT_LAW, "민법 원문 필요", List.of("적용 조문"),
                List.of(), List.of(), List.of(new EvidenceDecision.LawSearch("민법", EvidenceDecision.SearchField.TITLE, true, null)));
    }
    @Test void returnsOnlyServerAuthorizedReadyEvidenceWithProvenance() {
        var indexed = hit();
        when(rag.searchTop15(any(), eq("질문"))).thenReturn(List.of(indexed));
        when(planner.decide(eq("질문"), anyList())).thenAnswer(call -> ready(call.getArgument(1)));
        var evidence = collector.collect("질문");
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).kind()).isEqualTo("LAW_QDRANT");
        assertThat(evidence.get(0).provenance().sourceUuid()).isEqualTo(indexed.referenceId());
        verifyNoInteractions(laws, vectors);
    }
    @Test void emptyDatabaseCollectsAcrossAnEmptyFilteredPageThenQueriesAgain() {
        when(rag.searchTop15(any(), eq("질문"))).thenReturn(List.of(), List.of(hit()));
        when(planner.decide(eq("질문"), anyList())).thenAnswer(call -> {
            List<EvidenceItem> evidence = call.getArgument(1);
            return evidence.isEmpty() ? collect() : ready(evidence);
        });
        var candidate = new LegalToolContracts.Candidate("123", "001823", "민법", "20260101", null, null, null);
        when(laws.searchLaws(any(), any())).thenAnswer(call -> {
            LegalToolContracts.SearchRequest request = call.getArgument(1);
            assertThat(request.lawFilters().exactName()).isTrue();
            return new LegalToolContracts.SearchResult(request.page() == 1 ? LegalToolContracts.Status.EMPTY : LegalToolContracts.Status.AVAILABLE,
                    request.page() == 1 ? List.of() : List.of(candidate), request.page(), 20, 21,
                    request.page() == 1, Instant.now(), "https://www.law.go.kr/");
        });
        String source = UUID.randomUUID().toString();
        when(laws.getLawDetail(any(), eq("123"))).thenReturn(new LegalToolContracts.DetailResult(source, "123", "001823", "20260101",
                "민법", 1, true, Instant.now(), "https://www.law.go.kr/", List.of("1")));
        assertThat(collector.collect("질문")).hasSize(1);
        verify(laws, times(2)).searchLaws(any(), any());
        verify(vectors).vectorizeLaw(any(), eq(source));
        verify(rag, times(2)).searchTop15(any(), eq("질문"));
    }
    @Test void insufficientEvidenceStopsWithoutCollectingOrAnswering() {
        when(rag.searchTop15(any(), anyString())).thenReturn(List.of());
        when(planner.decide(anyString(), anyList())).thenReturn(new EvidenceDecision(EvidenceDecision.Action.INSUFFICIENT,
                "사실관계 부족", List.of("행위 내용"), List.of(), List.of(), List.of()));
        assertThatThrownBy(() -> collector.collect("질문")).isInstanceOfSatisfying(MolelawException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.EVIDENCE_INSUFFICIENT));
        verifyNoInteractions(laws, vectors);
    }
    @Test void rejectsModelInventedEvidenceIds() {
        when(rag.searchTop15(any(), anyString())).thenReturn(List.of(hit()));
        when(planner.decide(anyString(), anyList())).thenReturn(new EvidenceDecision(EvidenceDecision.Action.ANSWER_READY,
                "완료", List.of(), List.of(UUID.randomUUID().toString()), List.of(), List.of()));
        assertThatThrownBy(() -> collector.collect("질문")).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(laws, vectors);
    }
    @Test void embeddingOverloadIsReportedWithoutRetryStorm() {
        when(rag.searchTop15(any(), anyString())).thenThrow(new LegalToolException(LegalToolException.Reason.BUSY, true));
        assertThatThrownBy(() -> collector.collect("질문")).isInstanceOfSatisfying(MolelawException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.RETRIEVAL_BUSY));
        verify(rag).searchTop15(any(), anyString());
        verifyNoInteractions(planner, laws, vectors);
    }
    @Test void unavailableVectorConfigurationDoesNotFallBackToLegacyTables() {
        var disabled = new LegalEvidenceCollector(provider(null), provider(null), laws, planner);
        assertThatThrownBy(() -> disabled.collect("질문")).isInstanceOfSatisfying(MolelawException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.RETRIEVAL_UNAVAILABLE));
        verifyNoInteractions(planner, laws);
    }
}