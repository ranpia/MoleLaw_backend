package com.MoleLaw_backend.law.service.retrieval;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EvidenceDecisionPlannerTest {
    private final ChatModel model = mock(ChatModel.class);
    private final EvidenceDecisionPlanner planner = new EvidenceDecisionPlanner(ChatClient.builder(model).build(), new ObjectMapper());
    private final List<EvidenceItem> evidence = List.of(new EvidenceItem("known-id", "LAW_CHUNK", "chunk:1",
            "민법", "https://www.law.go.kr", "확보한 본문"));

    @Test
    void mapsProviderFailureWithoutLeakingItsMessageOrRetrying() {
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("private provider payload"));

        GptApiException failure = assertThrows(GptApiException.class, () -> planner.decide("질문", evidence));

        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.GPT_API_FAILURE);
        assertThat(failure.getMessage()).doesNotContain("private provider payload");
        verify(model).call(any(Prompt.class));
    }

    @Test
    void mapsMalformedModelDecisionToSafeFailure() {
        respond("private invalid model output");
        GptApiException failure = assertThrows(GptApiException.class, () -> planner.decide("질문", evidence));
        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.GPT_API_FAILURE);
        assertThat(failure.getMessage()).doesNotContain("private invalid model output");
    }

    @Test
    void doesNotAcceptAnAnswerDecisionWithAnUnknownEvidenceId() {
        respond("""
                {"action":"ANSWER_READY","reason":"충분함","missingIssues":[],"evidenceIds":["foreign-id"],
                 "searchQueries":[],"lawSearches":[]}
                """);
        GptApiException failure = assertThrows(GptApiException.class, () -> planner.decide("질문", evidence));
        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.GPT_API_FAILURE);
    }

    private void respond(String text) {
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }
}
