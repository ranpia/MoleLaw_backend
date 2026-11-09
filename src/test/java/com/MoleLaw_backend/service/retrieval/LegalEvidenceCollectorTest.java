package com.MoleLaw_backend.service.retrieval;

import com.MoleLaw_backend.dto.retrieval.*;
import com.MoleLaw_backend.service.tool.*;
import com.MoleLaw_backend.util.exception.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LegalEvidenceCollectorTest {
    private final QuestionRetrievalPlanner questions = mock(QuestionRetrievalPlanner.class);
    private final AiSearchEvidenceCollector ai = mock(AiSearchEvidenceCollector.class);
    private final LawRetrievalToolFactory factory = mock(LawRetrievalToolFactory.class);
    private final LawToolCallingPlanner planner = mock(LawToolCallingPlanner.class);
    private final QuestionRetrievalPlan plan = new QuestionRetrievalPlan(QuestionRetrievalPlan.Type.LAW_EXPLANATION,
            QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE, List.of("민법"), List.of(), List.of());
    private LegalEvidenceCollector collector(RetrievalBackend backend, boolean fixed) {
        return new LegalEvidenceCollector(questions, ai, factory, planner, backend, Duration.ofMinutes(3), fixed);
    }
    @Test void inScopeQuestionUsesTheToolCallingPlannerAndRequestBoundSession() {
        var session = mock(LawRetrievalToolSession.class);
        var result = new CollectedLegalEvidence(plan, List.of());
        when(questions.plan("질문")).thenReturn(plan);
        when(factory.create(eq("질문"), eq(plan), any(), any(), eq(false))).thenReturn(session);
        when(planner.collect("질문", plan, session, RetrievalObserver.NONE)).thenReturn(result);
        assertThat(collector(RetrievalBackend.QDRANT, false).collectForAnswer("질문")).isSameAs(result);
        verify(factory).requireAvailable();
        verifyNoInteractions(ai);
    }
    @Test void invalidQuestionStopsBeforeAnyModelOrTool() {
        for (String question : new String[]{"", " ", "가".repeat(2001)})
            assertThatThrownBy(() -> collector(RetrievalBackend.QDRANT, false).collect(question)).isInstanceOf(MolelawException.class);
        verifyNoInteractions(questions, factory, planner, ai);
    }
    @Test void unavailableVectorServicesStopBeforePaidQuestionPlanning() {
        doThrow(new MolelawException(ErrorCode.RETRIEVAL_UNAVAILABLE)).when(factory).requireAvailable();
        assertThatThrownBy(() -> collector(RetrievalBackend.QDRANT, false).collect("질문"))
                .isInstanceOfSatisfying(MolelawException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RETRIEVAL_UNAVAILABLE));
        verifyNoInteractions(questions, planner, ai);
    }
    @Test void outOfScopeQuestionNeverRunsTheToolPlanner() {
        when(questions.plan("날씨")).thenReturn(new QuestionRetrievalPlan(QuestionRetrievalPlan.Type.OUT_OF_SCOPE,
                QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE, List.of(), List.of(), List.of()));
        assertThatThrownBy(() -> collector(RetrievalBackend.QDRANT, false).collect("날씨")).isInstanceOf(MolelawException.class);
        verify(factory, never()).create(anyString(), any(), any(), any(), anyBoolean());
        verifyNoInteractions(planner);
    }
    @Test void fixedIndexOnlyCannotUseTheDiagnosticAiSearchBackend() {
        assertThatThrownBy(() -> collector(RetrievalBackend.AI_SEARCH, true).collect("질문")).isInstanceOf(MolelawException.class);
        verifyNoInteractions(questions, factory, planner, ai);
    }
    @Test void explicitAiSearchBackendRemainsAnExplicitDiagnosticRoute() {
        var expected = new CollectedLegalEvidence(plan, List.of());
        when(ai.collect("질문", RetrievalObserver.NONE)).thenReturn(expected);
        assertThat(collector(RetrievalBackend.AI_SEARCH, false).collectForAnswer("질문")).isSameAs(expected);
        verifyNoInteractions(factory, questions, planner);
    }
    @Test void busyAndProviderFailureKeepTheirPublicClassification() {
        when(questions.plan("질문")).thenReturn(plan);
        for (var reason : List.of(LegalToolException.Reason.BUSY, LegalToolException.Reason.API_FAILURE,
                LegalToolException.Reason.BUDGET_EXHAUSTED)) {
            doThrow(new LegalToolException(reason, false)).when(planner).collect(anyString(), any(), any(), any());
            var expected = reason == LegalToolException.Reason.BUSY ? ErrorCode.RETRIEVAL_BUSY
                    : reason == LegalToolException.Reason.BUDGET_EXHAUSTED ? ErrorCode.EVIDENCE_INSUFFICIENT : ErrorCode.RETRIEVAL_UNAVAILABLE;
            assertThatThrownBy(() -> collector(RetrievalBackend.QDRANT, false).collect("질문"))
                    .isInstanceOfSatisfying(MolelawException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
        }
    }
}
