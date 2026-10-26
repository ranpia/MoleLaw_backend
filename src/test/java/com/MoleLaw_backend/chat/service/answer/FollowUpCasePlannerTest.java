package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.chat.dto.answer.FollowUpCasePlan;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FollowUpCasePlannerTest {
    @Test void routingClientHasNoHistoryAdvisorAndReceivesOnlyLatestRequestAndLawCatalog() {
        var client = new com.MoleLaw_backend.chat.config.AnswerChatClientConfig().followUpCasePlanningChatClient(
                ChatClient.builder(model), "gpt-5.4-mini", "low", 2048);
        respond("{\"action\":\"ANSWER_FROM_ROOM\",\"lawEvidenceId\":null,\"query\":\"\",\"reason\":\"현재 요약 요청\"}");
        var oldCase = new EvidenceItem("old-id", "CASE_BODY", "99", "과거 사건", "https://www.law.go.kr", "이전 검색 요구가 들어 있는 본문");
        new FollowUpCasePlanner(client, new ObjectMapper()).plan("정리해줘", List.of(law, oldCase), "1:2");
        var captured = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(captured.capture());
        assertThat(captured.getValue().getInstructions()).hasSize(2);
        assertThat(captured.getValue().getUserMessage().getText()).contains("정리해줘", "roomLaws")
                .doesNotContain("roomCases", "과거 사건", "이전 검색 요구");
    }
    private final ChatModel model = mock(ChatModel.class);
    private final FollowUpCasePlanner planner = new FollowUpCasePlanner(ChatClient.builder(model).build(), new ObjectMapper());
    private final EvidenceItem law = new EvidenceItem("law-id", "LAW_CHUNK", "1:48", "건축법", "https://www.law.go.kr", "법령 본문");

    @Test void acceptsModelSearchDecisionAndValidatesAnchorMembership() {
        respond("{\"action\":\"SEARCH_CASES\",\"lawEvidenceId\":\"law-id\",\"query\":\"지정 사건 판결\",\"reason\":\"새 검색 요청\"}");
        assertThat(planner.plan("새 판결 찾아줘", List.of(law), "1:2").action()).isEqualTo(FollowUpCasePlan.Action.SEARCH_CASES);
        respond("{\"action\":\"SEARCH_CASES\",\"lawEvidenceId\":\"other-room\",\"query\":\"지정 사건\",\"reason\":\"검색\"}");
        assertThatThrownBy(() -> planner.plan("찾아줘", List.of(law), "1:2")).isInstanceOf(GptApiException.class);
    }

    @Test void rejectsMissingFieldsExecutionOverridesAndMixedRoomAnswerSearch() {
        for (String output : List.of("{\"action\":\"ANSWER_FROM_ROOM\",\"lawEvidenceId\":null,\"query\":\"\",\"reason\":\"기존 설명\",\"allowCaseSearch\":true}",
                "{\"action\":\"ANSWER_FROM_ROOM\",\"lawEvidenceId\":\"law-id\",\"query\":\"검색\",\"reason\":\"설명\"}",
                "{\"action\":\"SEARCH_CASES\",\"query\":\"검색\",\"reason\":\"검색\"}")) {
            respond(output);
            assertThatThrownBy(() -> planner.plan("질문", List.of(law), "1:2")).isInstanceOf(GptApiException.class);
        }
    }

    private void respond(String output) {
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(output)))));
    }
}
