package com.MoleLaw_backend.service;

import com.MoleLaw_backend.dto.response.AnswerResponse;
import com.MoleLaw_backend.dto.response.GptTitleAnswerResponse;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GptService {

    private final WebClient.Builder webClientBuilder;

    @Value("${openai.api-key}")
    private String openAiApiKey;

//    public GptTitleAnswerResponse generateTitleAndAnswer(String userMessage) {
//        // 프롬프트 구성
//        String prompt = """
//                아래 사용자 질문에 대한 적절한 챗봇 답변과, 이 질문을 대표할 수 있는 요약된 '제목'을 함께 만들어줘.
//                JSON 형식으로 응답해줘. 형식은 다음과 같아:
//                {
//                  "title": "요약된 제목",
//                  "answer": "GPT의 답변 내용"
//                }
//
//                사용자 질문: %s
//                """.formatted(userMessage);
//
//        // 요청 본문 구성
//        Map<String, Object> requestBody = new HashMap<>();
//        requestBody.put("model", "gpt-3.5-turbo");
//        requestBody.put("messages", List.of(
//                Map.of("role", "user", "content", prompt)
//        ));
//
//        try {
//            // OpenAI API 호출
//            String response = webClientBuilder.build()
//                    .post()
//                    .uri("https://api.openai.com/v1/chat/completions")
//                    .header("Authorization", "Bearer " + openAiApiKey)
//                    .header("Content-Type", "application/json")
//                    .bodyValue(requestBody)
//                    .retrieve()
//                    .bodyToMono(String.class)
//                    .block();
//
//            return parseJsonToGptTitleAnswerResponse(response);
//
//        } catch (WebClientResponseException e) {
//            // OpenAI 응답 실패 로그 출력
//            log.error("OpenAI API 호출 실패 - 상태코드: {}, 응답본문: {}", e.getStatusCode(), e.getResponseBodyAsString());
//            throw new GptApiException(ErrorCode.GPT_API_FAILURE, e.getResponseBodyAsString());
//        } catch (Exception e) {
//            log.error("GPT 응답 처리 중 예외 발생", e);
//            throw new RuntimeException("GPT 응답 파싱 실패", e);
//        }
//    }

    public String generateTitle(String userMessage) {
        // 프롬프트 구성
        String prompt = """
                아래 사용자 질문에 대하여 이 질문을 대표할 수 있는 요약된 '제목'을 한글로 만들어줘.
                오로지 String 형식의 문장 하나만으로 답해줘:
                
                사용자 질문: %s
                """.formatted(userMessage);

        // 요청 본문 구성
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "gpt-3.5-turbo");
        requestBody.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
        ));

        try {
            // OpenAI API 호출
            String response = webClientBuilder.build()
                    .post()
                    .uri("https://api.openai.com/v1/chat/completions")
                    .header("Authorization", "Bearer " + openAiApiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return parseTitleFromRawResponse(response);

        } catch (WebClientResponseException e) {
            // OpenAI 응답 실패 로그 출력
            log.error("OpenAI API 호출 실패 - 상태코드: {}, 응답본문: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new GptApiException(ErrorCode.GPT_API_FAILURE, e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("GPT 응답 처리 중 예외 발생", e);
            throw new RuntimeException("GPT 응답 파싱 실패", e);
        }
    }

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


//    private GptTitleAnswerResponse parseJsonToGptTitleAnswerResponse(String rawResponse) throws Exception {
//        ObjectMapper mapper = new ObjectMapper();
//
//        // choices[0].message.content 추출
//        JsonNode root = mapper.readTree(rawResponse);
//        String content = root.path("choices").get(0).path("message").path("content").asText();
//
//        // content는 우리가 원하는 JSON 문자열 → 다시 파싱
//        return mapper.readValue(content, GptTitleAnswerResponse.class);
//    }


    private String parseTitleFromRawResponse(String rawResponse) throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        // OpenAI 응답 JSON에서 choices[0].message.content 추출
        JsonNode root = mapper.readTree(rawResponse);
        String content = root.path("choices").get(0).path("message").path("content").asText();

        return content.trim();
    }

}
