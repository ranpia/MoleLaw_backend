package com.MoleLaw_backend.service.agents.ai;

import com.MoleLaw_backend.util.exception.ErrorCode;
import com.MoleLaw_backend.util.exception.GptApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AiChatService {
    private final ChatClient chatClient;

    public <T> T generateEntity(String system, String userPrompt, Class<T> type, double temperature) {
        T result = request(system, List.of(new UserMessage(userPrompt)),
                temperature).call().entity(type);
        if (result == null) {
            throw new GptApiException(ErrorCode.GPT_EMPTY_RESPONSE);
        }
        return result;
    }

    private ChatClient.ChatClientRequestSpec request(String system, List<Message> messages, double temperature) {
        return chatClient.prompt().system(system).messages(messages)
                .options(ChatOptions.builder().temperature(temperature).build());
    }
}
