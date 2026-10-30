package com.MoleLaw_backend.common.ai;

import com.MoleLaw_backend.law.dto.response.KeywordExtractionResponse;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
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
    void convertsStructuredKeywordResponse() {
        when(model.call(any(Prompt.class))).thenReturn(response(
                "{\"keywords\":[\"테스트법\"],\"summary\":\"상담 요약\",\"ministry\":\"기타\"}"));

        KeywordExtractionResponse result = service.generateEntity("system", "question",
                KeywordExtractionResponse.class, 0.3);

        assertEquals(List.of("테스트법"), result.getKeywords());
        assertEquals("상담 요약", result.getSummary());
        verify(model).call(any(Prompt.class));
    }

    @Test
    void mapsMalformedStructuredResponseToCommonError() {
        when(model.call(any(Prompt.class))).thenReturn(response("invalid-json"));

        GptApiException error = assertThrows(GptApiException.class,
                () -> service.generateEntity("system", "question", KeywordExtractionResponse.class, 0.3));

        assertEquals(ErrorCode.GPT_API_FAILURE, error.getErrorCode());
        verify(model).call(any(Prompt.class));
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
