package com.MoleLaw_backend.service.chat;

import com.MoleLaw_backend.dto.response.AnswerResponse;
import com.MoleLaw_backend.exception.GptApiException;
import com.MoleLaw_backend.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GptService {

    private final WebClient.Builder webClientBuilder;

    @Value("${openai.api-key}")
    private String openAiApiKey;

    public AnswerResponse generateAnswerWithContext(String firstUserQuestion, String lastUserQuestion) {
        String userPrompt = """
        사용자가 처음 질문한 내용은 다음과 같습니다:
        "%s"
        
        이 질문에는 참고할 법령과 판례 정보가 포함되어 있습니다.

        이후 사용자가 추가로 다음과 같이 질문했습니다:
        "%s"
        
        이 두 질문을 종합하여, 초기 질문의 맥락(법령/판례 정보)을 유지하면서
        후속 질문까지 반영하여 일관된 법률 상담을 제공해주세요.
        답변은 친절하고 명확하게 작성해주세요.
        """.formatted(firstUserQuestion, lastUserQuestion);

        Map<String, Object> requestBody = Map.of(
                "model", "gpt-4",
                "messages", List.of(
                        Map.of("role", "system", "content", "당신은 법률 전문가 어시스턴트입니다."),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        try {
            String response = webClientBuilder.build()
                    .post()
                    .uri("https://api.openai.com/v1/chat/completions")
                    .header("Authorization", "Bearer " + openAiApiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response);

            return new AnswerResponse(
                    root.path("choices").get(0).path("message").path("content").asText(),
                    ""
            );

        } catch (WebClientResponseException e) {
            log.error("GPT 응답 실패: {}", e.getResponseBodyAsString());
            throw new GptApiException(ErrorCode.GPT_API_FAILURE, e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("GPT 연속질문 응답 처리 중 예외", e);
            throw new RuntimeException("GPT 연속질문 응답 파싱 실패", e);
        }
    }

}
