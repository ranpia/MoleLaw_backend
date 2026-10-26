package com.MoleLaw_backend.law.service.retrieval;

import com.MoleLaw_backend.law.dto.retrieval.QuestionRetrievalPlan;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class QuestionRetrievalPlannerTest {
    private final ChatModel model = mock(ChatModel.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final QuestionRetrievalPlanner planner = new QuestionRetrievalPlanner(ChatClient.builder(model).build(), mapper);
    private static final String DISCOVERY = """
            {"type":"LAW_DISCOVERY","responseScope":"GENERAL_GUIDANCE",
             "searchQueries":["공항 주변 건축물 장애물 제한","건축물 높이 제한"],
             "lawSearches":[{"query":"공항시설법","field":"TITLE","exactName":true,"ministry":null}],
             "missingFacts":["부지 위치","실제 건축물 높이"],
             "analysis":{"title":"공항 주변 건축 검토","purpose":"관련 법령 안내",
               "facts":[{"text":"15층 건물 신축 계획","sourceQuote":"15층 건물을 지으려는"}],
               "issues":[{"id":"Q1","request":"검토할 법령 안내","sourceQuote":"고려해야할 법령이 필요해",
                 "searchTerms":["공항 주변 건축물 장애물 제한"],"mentionedTargets":[],"candidateTargets":["공항시설법"],"missingFacts":["부지 위치"]}],
               "relations":[],"ambiguities":[]}}
            """;

    @Test void legalDiscoveryAllowsMissingCaseFactsAndProducesDistinctQueries() {
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(DISCOVERY)))));
        var result = planner.plan("공항 근처에 15층 건물을 지으려는 데 고려해야할 법령이 필요해");
        assertThat(result.type()).isEqualTo(QuestionRetrievalPlan.Type.LAW_DISCOVERY);
        assertThat(result.responseScope()).isEqualTo(QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE);
        assertThat(result.searchQueries()).containsExactly("공항 주변 건축물 장애물 제한", "건축물 높이 제한");
        assertThat(result.missingFacts()).contains("부지 위치");
        verify(model, times(1)).call(any(Prompt.class));
    }

    @Test void missingFactsCannotAuthorizeASpecificAssessment() {
        String invalid = DISCOVERY.replace("LAW_DISCOVERY", "CASE_ASSESSMENT")
                .replace("GENERAL_GUIDANCE", "SPECIFIC_ASSESSMENT");
        assertThatThrownBy(() -> QuestionRetrievalPlan.parse(invalid, mapper)).isInstanceOf(IllegalArgumentException.class);
        var limited = QuestionRetrievalPlan.parse(DISCOVERY.replace("LAW_DISCOVERY", "CASE_ASSESSMENT"), mapper);
        assertThat(limited.responseScope()).isEqualTo(QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE);
    }

    @Test void missingAnalysisAndInventedQuotationFailWithoutSilentFallback() throws Exception {
        var missing = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(DISCOVERY);
        missing.remove("analysis");
        var absent = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(DISCOVERY);
        absent.putNull("analysis");
        for (String output : List.of(DISCOVERY.replace("15층 건물을 지으려는", "제공되지 않은 사실"),
                missing.toString(), absent.toString())) {
            when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(output)))));
            assertThatThrownBy(() -> planner.plan("공항 근처에 15층 건물을 지으려는 데 고려해야할 법령이 필요해"))
                    .isInstanceOf(GptApiException.class);
        }
    }

    @Test void unspacedCompoundQuestionKeepsLaterFireAndTemporalRequests() throws Exception {
        String question = "대형건축물이나다중이용업소가입점하는건물의리모델링을진행하려고합니다."
                + "건축법에서는방화구획및피난계단설치기준을충족하여허가를받았으나소방법상특정소방대상물기준이적용되어"
                + "스프링클러및제연설비의추가설치가필요하다고합니다."
                + "1.건축법상건축허가를받았더라도소방관서의동의를얻지못하면사용승인이불가능한지"
                + "2.용도변경이나증축시소방시설설치기준이강화된경우기존건물에도소급적용되는지";
        var analysis = new com.MoleLaw_backend.law.dto.retrieval.QuestionAnalysis("복합 기준과 적용 시점", "관계 및 적용 범위 설명", List.of(),
                List.of(new com.MoleLaw_backend.law.dto.retrieval.QuestionAnalysis.Issue("Q1", "허가와 동의 및 사용승인의 관계",
                                "소방관서의동의를얻지못하면사용승인이불가능한지", List.of("소방 동의 사용승인 기준 관계"),
                                List.of("건축법", "소방법"), List.of(), List.of()),
                        new com.MoleLaw_backend.law.dto.retrieval.QuestionAnalysis.Issue("Q2", "기존 건물에 대한 강화 기준의 적용",
                                "기존건물에도소급적용되는지", List.of("소방시설 기준 기존 건물 적용 특례 경과규정"),
                                List.of("소방법"), List.of(), List.of("용도변경 시점"))),
                List.of(new com.MoleLaw_backend.law.dto.retrieval.QuestionAnalysis.Relation("Q1", "Q2",
                        com.MoleLaw_backend.law.dto.retrieval.QuestionAnalysis.RelationType.TEMPORAL)), List.of("소방법의 구체적인 명칭"));
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(DISCOVERY);
        node.set("analysis", mapper.valueToTree(analysis));
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(node.toString())))));
        var result = planner.plan(question);
        assertThat(result.analysis().issues()).extracting(com.MoleLaw_backend.law.dto.retrieval.QuestionAnalysis.Issue::id)
                .containsExactly("Q1", "Q2");
        assertThat(result.initialSearchQueries()).containsExactly("소방 동의 사용승인 기준 관계", "소방시설 기준 기존 건물 적용 특례 경과규정");
        var prompt = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getUserMessage().getText()).isEqualTo(question);
    }

    @Test void unknownExecutionFieldsMissingFieldsAndTrailingJsonAreRejected() {
        for (String invalid : List.of(DISCOVERY.replace("\"type\":", "\"maxCalls\":999,\"type\":"),
                DISCOVERY.replace("\"type\":\"LAW_DISCOVERY\",", ""), DISCOVERY + "{}")) {
            assertThatThrownBy(() -> QuestionRetrievalPlan.parse(invalid, mapper)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void providerErrorsAreSanitizedAndNotRetried() {
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("private provider message"));
        assertThatThrownBy(() -> planner.plan("질문")).isInstanceOf(GptApiException.class)
                .hasMessageNotContaining("private provider message");
        verify(model, times(1)).call(any(Prompt.class));
    }
}
