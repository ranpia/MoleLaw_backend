package com.MoleLaw_backend.law.dto.retrieval;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuestionAnalysisTest {
    @Test void fourIndependentRequestsAreNotReducedToThreeSearchSlots() {
        var issues = List.of(issue("Q1", "첫 조건", "조건 비교"), issue("Q2", "둘째 절차", "절차 안내"),
                issue("Q3", "셋째 예외", "예외 요건"), issue("Q4", "마지막 적용 시점", "기준 적용 시점"));
        var analysis = new QuestionAnalysis("복합 요청", "비교와 절차 확인", List.of(), issues,
                List.of(new QuestionAnalysis.Relation("Q1", "Q4", QuestionAnalysis.RelationType.TEMPORAL)), List.of());
        analysis.validateAgainst("첫 조건을 비교하고 둘째 절차와 셋째 예외, 마지막 적용 시점을 알려줘", false);
        var plan = new QuestionRetrievalPlan(QuestionRetrievalPlan.Type.LAW_EXPLANATION,
                QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE,
                List.of("요약 검색 하나", "요약 검색 둘", "요약 검색 셋"), List.of(), List.of(), analysis);
        assertThat(plan.initialSearchQueries()).containsExactly("조건 비교", "절차 안내", "예외 요건", "기준 적용 시점");
    }

    @Test void factsAndMentionedTargetsMustHaveAnActualSourceInTheQuestion() {
        var analysis = new QuestionAnalysis("요청", "설명", List.of(new QuestionAnalysis.Fact("승인 완료", "승인받았다")),
                List.of(issue("Q1", "절차", "신청 절차")), List.of(), List.of());
        assertThatThrownBy(() -> analysis.validateAgainst("절차를 알려줘", false)).isInstanceOf(IllegalArgumentException.class);
        var named = new QuestionAnalysis.Issue("Q1", "절차", "절차", List.of("신청 절차"), List.of("특정법"), List.of(), List.of());
        var invented = new QuestionAnalysis("요청", "설명", List.of(), List.of(named), List.of(), List.of());
        assertThatThrownBy(() -> invented.validateAgainst("절차를 알려줘", false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void duplicateIdsAndUnknownRelationEndpointsAreRejected() {
        assertThatThrownBy(() -> new QuestionAnalysis("요청", "설명", List.of(),
                List.of(issue("Q1", "첫", "검색1"), issue("Q1", "뒤", "검색2")), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuestionAnalysis("요청", "설명", List.of(), List.of(issue("Q1", "첫", "검색")),
                List.of(new QuestionAnalysis.Relation("Q1", "Q2", QuestionAnalysis.RelationType.CONFLICT)), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void unresolvedPerIssueFactsCannotAuthorizeSpecificAssessment() {
        var item = new QuestionAnalysis.Issue("Q1", "판단", "판단", List.of("기준"), List.of(), List.of(), List.of("적용 시점"));
        var analysis = new QuestionAnalysis("판단", "판단", List.of(), List.of(item), List.of(), List.of());
        assertThatThrownBy(() -> new QuestionRetrievalPlan(QuestionRetrievalPlan.Type.CASE_ASSESSMENT,
                QuestionRetrievalPlan.ResponseScope.SPECIFIC_ASSESSMENT, List.of("기준"), List.of(), List.of(), analysis))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private QuestionAnalysis.Issue issue(String id, String quote, String query) {
        return new QuestionAnalysis.Issue(id, query, quote, List.of(query), List.of(), List.of(), List.of());
    }
}
