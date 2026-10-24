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
    private final ChatModel model = mock(ChatModel.class);
    private final CaseQueryPlanner planner = new CaseQueryPlanner(ChatClient.builder(model).build(), new ObjectMapper());
    private final EvidenceItem law = new EvidenceItem("law", "LAW_CHUNK", "1:61", "근로기준법 제61조", "https://www.law.go.kr", "사용 촉진 규정");
    @Test void acceptsBoundedDistinctLegalTermsAndRejectsMalformedPlans() {
        when(model.call(any(Prompt.class))).thenReturn(response("{\"queries\":[\"연차휴가\",\"연차수당\",\"사용촉진\"]}"));
        assertThat(planner.plan("일이 바빠 휴가를 못 썼어요", law).queries()).containsExactly("연차휴가", "연차수당", "사용촉진");
        for (String invalid : List.of("null", "{\"queries\":[]}", "{\"queries\":[\"연차휴가\",\"연차 휴가!\"]}",
                "{\"queries\":[\"" + "가".repeat(81) + "\"]}", "{\"queries\":[\"연차\"],\"unexpected\":true}")) {
            when(model.call(any(Prompt.class))).thenReturn(response(invalid));
            assertThatThrownBy(() -> planner.plan("질문", law)).isInstanceOf(GptApiException.class);
        }
    }
    private ChatResponse response(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
}
