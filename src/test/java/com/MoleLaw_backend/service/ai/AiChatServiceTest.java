package com.MoleLaw_backend.service.ai;

import com.MoleLaw_backend.dto.response.KeywordAndTitleResponse;
import com.MoleLaw_backend.exception.ErrorCode;
import com.MoleLaw_backend.exception.GptApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AiChatServiceTest {
    private ChatModel model;
    private AiChatService service;

    @BeforeEach
    void setUp() {
        model = mock(ChatModel.class);
        service = new AiChatService(ChatClient.builder(model).build());
    }

    @Test
    void preservesSystemAssistantAndUserRolesAndCallsModelOnce() {
        when(model.call(any(Prompt.class))).thenReturn(response("후속 답변"));

        String result = service.generateText("시스템 지시", List.of(
                new AssistantMessage("이전 답변"), new UserMessage("후속 질문 {literal}")), 0.5);

        assertEquals("후속 답변", result);
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertEquals(List.of(MessageType.SYSTEM, MessageType.ASSISTANT, MessageType.USER),
                prompt.getValue().getInstructions().stream().map(message -> message.getMessageType()).toList());
        assertEquals("후속 질문 {literal}", prompt.getValue().getInstructions().get(2).getText());
        assertEquals(0.5, prompt.getValue().getOptions().getTemperature());
    }

    @Test
    void rejectsBlankModelAnswer() {
        when(model.call(any(Prompt.class))).thenReturn(response(" "));

        GptApiException error = assertThrows(GptApiException.class,
                () -> service.generateText("system", List.of(new UserMessage("question")), 0.5));

        assertEquals(ErrorCode.GPT_EMPTY_RESPONSE, error.getErrorCode());
        verify(model).call(any(Prompt.class));
    }

    @Test
    void mapsModelFailureWithoutExposingProviderMessageOrRepeatingCall() {
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("private provider response"));

        GptApiException error = assertThrows(GptApiException.class,
                () -> service.generateText("system", List.of(new UserMessage("question")), 0.5));

        assertEquals(ErrorCode.GPT_API_FAILURE, error.getErrorCode());
        assertFalse(error.getMessage().contains("private provider response"));
        verify(model).call(any(Prompt.class));
    }

    @Test
    void convertsStructuredKeywordResponse() {
        when(model.call(any(Prompt.class))).thenReturn(response(
                "{\"keywords\":[\"테스트법\"],\"summary\":\"상담 요약\",\"ministry\":\"기타\"}"));

        KeywordAndTitleResponse result = service.generateEntity("system", "question",
                KeywordAndTitleResponse.class, 0.3);

        assertEquals(List.of("테스트법"), result.getKeywords());
        assertEquals("상담 요약", result.getSummary());
        verify(model).call(any(Prompt.class));
    }

    @Test
    void mapsMalformedStructuredResponseToCommonError() {
        when(model.call(any(Prompt.class))).thenReturn(response("invalid-json"));

        GptApiException error = assertThrows(GptApiException.class,
                () -> service.generateEntity("system", "question", KeywordAndTitleResponse.class, 0.3));

        assertEquals(ErrorCode.GPT_API_FAILURE, error.getErrorCode());
        verify(model).call(any(Prompt.class));
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
