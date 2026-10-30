package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.common.exception.MolelawException;
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

class InitialQuestionSuggestionServiceTest {
    private final ChatModel model = mock(ChatModel.class);
    private final InitialQuestionSuggestionService service = new InitialQuestionSuggestionService(ChatClient.builder(model).build(), new ObjectMapper());

    @Test void preservesEveryOriginalFactAndReturnsServerAssignedIdsWithinConsultationLimit() {
        String original = "  시공 하자와 제설하지 않은 관리자의 책임을 비교하고 공동책임도 궁금합니다.  ";
        respond("{\"suggestions\":[{\"label\":\"책임 비교\",\"focus\":\"위 상황에서 책임을 비교하는 기준을 설명해 주세요.\"},"
                + "{\"label\":\"공동책임\",\"focus\":\"공동책임의 일반 기준과 추가 확인할 정보를 알려주세요.\"}]}");
        var result = service.suggest(original);
        assertThat(result.suggestedQuestions()).hasSize(2);
        assertThat(result.suggestedQuestions()).extracting(q -> q.id()).containsExactly("suggestion-1", "suggestion-2");
        assertThat(result.suggestedQuestions()).allSatisfy(q -> assertThat(q.question()).startsWith(original + "\n\n"));
        var prompt = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getUserMessage().getText()).contains("focusMaxCharacters", "제설하지 않은");
    }

    @Test void longDraftIsNeverTruncatedOrSentToModel() {
        String original = "😀".repeat(2000);
        assertThat(service.suggest(original).suggestedQuestions()).singleElement().satisfies(q -> assertThat(q.question()).isEqualTo(original));
        verify(model, never()).call(any(Prompt.class));
    }

    @Test void malformedDuplicateOversizedAndExecutionOverrideOutputsFailWithoutReturningQuestions() {
        for (String output : List.of("null", "{\"suggestions\":[]}", "{\"suggestions\":[{\"label\":\"요약\"}]}",
                "{\"suggestions\":[{\"label\":\"요약\",\"focus\":\"\"}]}",
                "{\"suggestions\":[{\"label\":\"요약\",\"focus\":\"질문\",\"allowCaseSearch\":true}]}",
                "{\"suggestions\":[{\"label\":\"동일\",\"focus\":\"질문1\"},{\"label\":\"동일\",\"focus\":\"질문2\"}]}",
                "{\"suggestions\":[{\"label\":\"요약\",\"focus\":\"" + "가".repeat(301) + "\"}]}")) {
            respond(output);
            assertThatThrownBy(() -> service.suggest("책임 기준이 궁금합니다.")).isInstanceOf(GptApiException.class);
        }
    }

    @Test void invalidUserInputDoesNotCallModel() {
        for (String input : new String[]{null, " ", "가".repeat(2001)})
            assertThatThrownBy(() -> service.suggest(input)).isInstanceOf(MolelawException.class);
        verify(model, never()).call(any(Prompt.class));
    }

    private void respond(String text) {
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }
}
