package com.MoleLaw_backend.service.ai;

import com.MoleLaw_backend.exception.ErrorCode;
import com.MoleLaw_backend.exception.GptApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {
    private final ChatClient chatClient;

    public String generateText(String system, List<Message> messages, double temperature) {
        try {
            String content = request(system, messages, temperature).call().content();
            if (content == null || content.isBlank()) {
                throw new GptApiException(ErrorCode.GPT_EMPTY_RESPONSE);
            }
            return content;
        } catch (GptApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("모델 답변 생성 실패: errorType={}", e.getClass().getSimpleName());
            throw new GptApiException(ErrorCode.GPT_API_FAILURE);
        }
    }

    public <T> T generateEntity(String system, String userPrompt, Class<T> type, double temperature) {
        try {
            T result = request(system, List.of(new UserMessage(userPrompt)),
                    temperature).call().entity(type);
            if (result == null) {
                throw new GptApiException(ErrorCode.GPT_EMPTY_RESPONSE);
            }
            return result;
        } catch (GptApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("모델 구조화 응답 생성 실패: errorType={}", e.getClass().getSimpleName());
            throw new GptApiException(ErrorCode.GPT_API_FAILURE);
        }
    }

    private ChatClient.ChatClientRequestSpec request(String system, List<Message> messages, double temperature) {
        return chatClient.prompt().system(system).messages(messages)
                .options(ChatOptions.builder().temperature(temperature).build());
    }
}
