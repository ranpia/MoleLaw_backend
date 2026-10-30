package com.MoleLaw_backend.law.util;

import com.MoleLaw_backend.law.dto.retrieval.EvidenceDecision;
import com.MoleLaw_backend.law.dto.retrieval.RetrievalAttempt;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LawCollectionQueueTest {
    @Test void laterConditionsRemainPendingAndCompletedConditionsAreNotSilentlyRequeued() {
        var queue = new LawCollectionQueue();
        var history = new ArrayList<RetrievalAttempt>();
        var first = condition("첫 법령"); var second = condition("둘째 법령"); var third = condition("셋째 법령");
        queue.offer(List.of(first, second, third), history);
        queue.record(first, RetrievalAttempt.Outcome.INDEXED, history);
        queue.offer(List.of(first), history);
        assertThat(queue.pending()).containsExactly(second, third);
        queue.record(second, RetrievalAttempt.Outcome.EXHAUSTED, history);
        queue.record(third, RetrievalAttempt.Outcome.DEFERRED_LIMIT, history);
        assertThat(queue.pending()).isEmpty();
        var view = RetrievalProgressInput.build(null, history, new ObjectMapper());
        assertThat(view.path("lawCollection")).hasSize(3);
        assertThat(view.path("lawCollection").get(0).path("state").asText()).isEqualTo("INDEXED");
        assertThat(view.path("lawCollection").get(1).path("state").asText()).isEqualTo("EXHAUSTED");
        assertThat(view.path("lawCollection").get(2).path("state").asText()).isEqualTo("DEFERRED_LIMIT");
    }

    private EvidenceDecision.LawSearch condition(String query) {
        return new EvidenceDecision.LawSearch(query, EvidenceDecision.SearchField.TITLE, true, null);
    }

    @Test void queueStateSurvivesHistoryTrimmingAndDoesNotCountAsExternalCalls() {
        var queue = new LawCollectionQueue(); var history = new ArrayList<RetrievalAttempt>();
        var first = condition("첫 법령"); var second = condition("둘째 법령");
        queue.offer(List.of(first, second), history);
        for (int i = 0; i < 20; i++) history.add(new RetrievalAttempt(RetrievalAttempt.Kind.VECTOR_SEARCH,
                "질의" + i, null, null, RetrievalAttempt.Outcome.AVAILABLE, 15, false));
        queue.record(first, RetrievalAttempt.Outcome.EXHAUSTED, history);
        var input = new EvidenceDecisionInputBuilder(new ObjectMapper(), 2048).build("질문", List.of(), null, history);
        assertThat(input.path("searchAttempts").size()).isLessThanOrEqualTo(8);
        assertThat(input.path("retrievalProgress").path("lawCollection")).hasSize(2);
        assertThat(input.path("retrievalProgress").path("lawCollection").get(1).path("state").asText()).isEqualTo("PENDING");
        assertThat(input.path("executionState").path("lawSearchCount").asInt()).isZero();
        assertThat(input.path("executionState").path("nonProductiveSearchCount").asInt()).isZero();
        assertThat(input.path("executionState").path("queryRevisionRequired").asBoolean()).isFalse();
    }

    @Test void issueSearchExecutionIsReportedWithoutDeclaringSufficiency() {
        var analysis = new com.MoleLaw_backend.law.dto.retrieval.QuestionAnalysis("제목", "설명", List.of(),
                List.of(new com.MoleLaw_backend.law.dto.retrieval.QuestionAnalysis.Issue("Q1", "첫 요청", "원문", List.of("질의1"), List.of(), List.of(), List.of()),
                        new com.MoleLaw_backend.law.dto.retrieval.QuestionAnalysis.Issue("Q2", "뒤쪽 요청", "원문", List.of("질의2"), List.of(), List.of(), List.of())),
                List.of(), List.of());
        var plan = new com.MoleLaw_backend.law.dto.retrieval.QuestionRetrievalPlan(
                com.MoleLaw_backend.law.dto.retrieval.QuestionRetrievalPlan.Type.LAW_EXPLANATION,
                com.MoleLaw_backend.law.dto.retrieval.QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE,
                List.of("요약"), List.of(), List.of(), analysis);
        var history = List.of(new RetrievalAttempt(RetrievalAttempt.Kind.VECTOR_SEARCH, "질의1", null, null,
                RetrievalAttempt.Outcome.AVAILABLE, 15, false));
        var view = RetrievalProgressInput.build(plan, history, new ObjectMapper());
        assertThat(view.path("issueSearches").get(0).path("searched").asBoolean()).isTrue();
        assertThat(view.path("issueSearches").get(1).path("searched").asBoolean()).isFalse();
        assertThat(view.path("candidatePresenceIsSufficiency").asBoolean()).isFalse();
    }
}
