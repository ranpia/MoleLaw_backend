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
             "missingFacts":["부지 위치","실제 건축물 높이"]}
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
