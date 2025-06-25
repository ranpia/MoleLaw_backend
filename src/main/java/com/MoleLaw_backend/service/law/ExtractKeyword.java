package com.MoleLaw_backend.service.law;

import com.MoleLaw_backend.exception.ErrorCode;
import com.MoleLaw_backend.exception.GptApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ExtractKeyword {

    @Value("${openai.api-key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<String> extractKeywords(String userInput) {
        try {
            String prompt = String.format("""
            다음 문장에서 법적 판례 검색에 유용한 핵심 키워드 3~5개를 JSON 배열로 출력하세요.
            문장: "%s"
            예시 출력: ["부당해고", "근로자 권리"]
            """, userInput);

            Map<String, Object> requestBody = Map.of(
                    "model", "gpt-4",
                    "messages", List.of(
                            Map.of("role", "system", "content", "당신은 법률 키워드 추출 도우미입니다."),
                            Map.of("role", "user", "content", prompt)
                    ),
                    "temperature", 0.3
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    "https://api.openai.com/v1/chat/completions",
                    requestEntity,
                    Map.class
            );

            Map<String, Object> body = response.getBody();
            if (body == null || body.get("choices") == null) {
                throw new GptApiException(ErrorCode.GPT_EMPTY_RESPONSE, "GPT 키워드 응답이 비어 있음");
            }

            String content = (String) ((Map<String, Object>) ((Map<String, Object>) ((List<?>) body.get("choices")).get(0)).get("message")).get("content");

            return objectMapper.readValue(content, new TypeReference<List<String>>() {});

        } catch (Exception e) {
            throw new GptApiException(ErrorCode.GPT_API_FAILURE, "GPT 키워드 추출 실패", e);
        }
    }

}
