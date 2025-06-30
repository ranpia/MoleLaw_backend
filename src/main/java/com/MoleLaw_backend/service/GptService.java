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


    public AnswerResponse generateAnswer(String userMessage) {
        String prompt = """
        당신은 법률 전문가 어시스턴트입니다. 사용자의 질문에 자연스럽고 유익한 대화를 이어가세요.
        사용자 질문: %s
        """.formatted(userMessage);

        Map<String, Object> requestBody = Map.of(
                "model", "gpt-4",
                "messages", List.of(
                        Map.of("role", "system", "content", "당신은 법률 대화 어시스턴트입니다."),
                        Map.of("role", "user", "content", prompt)
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
            //return root.path("choices").get(0).path("message").path("content").asText();
            //return parseJsonToGptTitleAnswerResponse(response);
            return new AnswerResponse(root.path("choices").get(0).path("message").path("content").asText(), "");

        } catch (WebClientResponseException e) {
            log.error("GPT 응답 실패: {}", e.getResponseBodyAsString());
            throw new GptApiException(ErrorCode.GPT_API_FAILURE, e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("GPT 일반 대화 처리 중 예외", e);
            throw new RuntimeException("GPT 일반 응답 파싱 실패", e);
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
