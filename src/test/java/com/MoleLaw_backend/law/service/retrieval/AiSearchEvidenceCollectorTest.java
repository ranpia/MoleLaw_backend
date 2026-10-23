package com.MoleLaw_backend.law.service.retrieval;

import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.law.dto.retrieval.*;
import com.MoleLaw_backend.law.service.tool.LawAiSearchToolService;
import com.MoleLaw_backend.law.dto.tool.AiLawSearchRequest;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AiSearchEvidenceCollectorTest {
    private final LawAiSearchToolService search = mock(LawAiSearchToolService.class);
    private final QuestionRetrievalPlanner questions = mock(QuestionRetrievalPlanner.class);
    private final EvidenceDecisionPlanner planner = mock(EvidenceDecisionPlanner.class);
    private final AiSearchEvidenceCollector collector = new AiSearchEvidenceCollector(search, questions, planner);

    @Test void repeatedQueryUsesTheNextApiPageInsteadOfBuildingAnIndex() {
        var plan = new QuestionRetrievalPlan(QuestionRetrievalPlan.Type.LAW_DISCOVERY,
                QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE, List.of("공항"), List.of(), List.of());
        when(questions.plan(anyString())).thenReturn(plan);
        var item = new EvidenceItem(UUID.randomUUID().toString(), "LAW_AI_SEARCH", "123:1", "검증법", "https://www.law.go.kr", "본문");
        var unrelated = new EvidenceItem(UUID.randomUUID().toString(), "LAW_AI_SEARCH", "999:1", "다른 법령", "https://www.law.go.kr", "다른 본문");
        when(search.search(any(), any())).thenAnswer(call -> {
            AiLawSearchRequest request = call.getArgument(1);
            return request.page() == 1 ? new AiLawSearchPage(1, 15, 16, true, List.of(unrelated))
                    : new AiLawSearchPage(2, 15, 16, false, List.of(item));
        });
        var rounds = new java.util.concurrent.atomic.AtomicInteger();
        when(planner.decide(anyString(), anyList(), eq(plan), anyList(), anyInt())).thenAnswer(call -> {
            return rounds.getAndIncrement() == 0 ? new EvidenceDecision(EvidenceDecision.Action.SEARCH_AGAIN, "다음 페이지", List.of("근거"),
                    List.of(), List.of("공항"), List.of()) : new EvidenceDecision(EvidenceDecision.Action.ANSWER_READY,
                    "확보", List.of(), List.of(item.id()), List.of(), List.of());
        });
        assertThat(collector.collect("질문").evidence()).containsExactly(item);
        verify(search).search(any(), eq(new AiLawSearchRequest("공항", 2, 15)));
    }

    @Test void unsupportedCollectionActionCannotFallBackToVectorization() {
        var plan = new QuestionRetrievalPlan(QuestionRetrievalPlan.Type.LAW_DISCOVERY,
                QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE, List.of("공항"), List.of(), List.of());
        when(questions.plan(anyString())).thenReturn(plan);
        when(search.search(any(), any())).thenReturn(new AiLawSearchPage(1, 15, 0, false, List.of()));
        when(planner.decide(anyString(), anyList(), any(), anyList(), anyInt())).thenReturn(
                new EvidenceDecision(EvidenceDecision.Action.COLLECT_LAW, "수집", List.of("법령"), List.of(), List.of(),
                        List.of(new EvidenceDecision.LawSearch("공항", EvidenceDecision.SearchField.TITLE, false, null))));
        assertThatThrownBy(() -> collector.collect("질문")).isInstanceOf(com.MoleLaw_backend.common.ai.exception.GptApiException.class);
    }
}
