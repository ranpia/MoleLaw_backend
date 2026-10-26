package com.MoleLaw_backend.law.service.retrieval;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class CaseQueryPlannerTest {
    @Test void repairsUnobservedTargetOnceWithoutLosingTheEventInTheAcceptedIssue() {
        when(model.call(any(Prompt.class))).thenReturn(
                response("{\"queries\":[\"성수대교 붕괴사고\"],\"intent\":\"SPECIFIC_CASE\",\"targets\":[\"성수대교 붕괴사고\"],\"scope\":\"BODY\"}"),
                response("{\"queries\":[\"성수대교\"],\"intent\":\"SPECIFIC_CASE\",\"targets\":[\"성수대교\"],\"scope\":\"BODY\"}"));
        var plan = planner.plan("성수대교 사건 직접 판례", law, "네, 검색해 주세요", null);
        assertThat(plan.targets()).containsExactly("성수대교");
        var prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompts.capture());
        assertThat(prompts.getAllValues().get(1).getUserMessage().getText()).contains("TARGET_GROUNDING", "성수대교 사건 직접 판례");
        var options = (org.springframework.ai.openai.OpenAiChatOptions) prompts.getValue().getOptions();
        assertThat(options.getResponseFormat().getType()).isEqualTo(org.springframework.ai.openai.api.ResponseFormat.Type.JSON_SCHEMA);
    }
    private final ChatModel model = mock(ChatModel.class);
    private final CaseQueryPlanner planner = new CaseQueryPlanner(ChatClient.builder(model).build(), new ObjectMapper());
    private final EvidenceItem law = new EvidenceItem("law", "LAW_CHUNK", "1:61", "근로기준법 제61조", "https://www.law.go.kr", "사용 촉진 규정");
    @Test void acceptsBoundedDistinctLegalTermsAndRejectsMalformedPlans() {
        when(model.call(any(Prompt.class))).thenReturn(response("{\"queries\":[\"연차휴가\",\"연차수당\",\"사용촉진\"],\"intent\":\"SIMILAR_ISSUE\",\"targets\":[],\"scope\":\"BODY\"}"));
        assertThat(planner.plan("일이 바빠 휴가를 못 썼어요", law).queries()).containsExactly("연차휴가", "연차수당", "사용촉진");
        for (String invalid : List.of("null", "{\"queries\":[]}", "{\"queries\":[\"연차휴가\",\"연차 휴가!\"]}",
                "{\"queries\":[\"" + "가".repeat(81) + "\"]}", "{\"queries\":[\"연차\"],\"unexpected\":true}")) {
            when(model.call(any(Prompt.class))).thenReturn(response(invalid));
            assertThatThrownBy(() -> planner.plan("질문", law)).isInstanceOf(GptApiException.class);
        }
    }

    @Test void modelChoosesSpecificCaseAndScopeWhileTargetsMustBeObservedAndRetained() {
        String output = "{\"queries\":[\"삼풍백화점\"],\"intent\":\"SPECIFIC_CASE\",\"targets\":[\"삼풍백화점\"],\"scope\":\"BODY\"}";
        when(model.call(any(Prompt.class))).thenReturn(response(output));
        var result = planner.plan("구조안전 관련 판결", law, "삼풍백화점 판결을 찾아줘", com.MoleLaw_backend.law.dto.retrieval.CaseSearchScope.LAW_REFERENCE);
        assertThat(result.intent()).isEqualTo(com.MoleLaw_backend.law.dto.retrieval.CaseQueryPlan.Intent.SPECIFIC_CASE);
        assertThat(result.scope()).isEqualTo(com.MoleLaw_backend.law.dto.retrieval.CaseSearchScope.BODY);
        assertThat(result.targets()).containsExactly("삼풍백화점");
        when(model.call(any(Prompt.class))).thenReturn(response(output.replace("삼풍백화점", "제공되지 않은 사건")));
        assertThatThrownBy(() -> planner.plan("구조안전 관련 판결", law, "삼풍백화점 판결을 찾아줘", null)).isInstanceOf(GptApiException.class);
        when(model.call(any(Prompt.class))).thenReturn(response(output.replace("\"queries\":[\"삼풍백화점\"]", "\"queries\":[\"건축법\"]")));
        assertThatThrownBy(() -> planner.plan("삼풍백화점 판결", law)).isInstanceOf(GptApiException.class);
    }
    private ChatResponse response(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
}
