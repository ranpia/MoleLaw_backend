package com.MoleLaw_backend.service.chat;

import com.MoleLaw_backend.dto.response.GptAnswerResponse;
import com.MoleLaw_backend.service.ai.AiChatService;
import com.MoleLaw_backend.service.ai.AiPrompts;
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
