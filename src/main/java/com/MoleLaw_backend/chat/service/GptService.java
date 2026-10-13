package com.MoleLaw_backend.chat.service;

import com.MoleLaw_backend.chat.dto.response.GptAnswerResponse;
import com.MoleLaw_backend.common.ai.AiChatService;
import com.MoleLaw_backend.common.ai.AiPrompts;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GptService {
    private final AiChatService aiChatService;
    private final AiPrompts prompts;

    public GptAnswerResponse generateAnswerWithContext(String firstAssistantAnswer, String lastUserQuestion) {
        String answer = aiChatService.generateText(prompts.getLegalSystem(), List.of(
                new AssistantMessage(firstAssistantAnswer), new UserMessage(lastUserQuestion)), 0.5);
        return new GptAnswerResponse(answer, "");
    }
}
