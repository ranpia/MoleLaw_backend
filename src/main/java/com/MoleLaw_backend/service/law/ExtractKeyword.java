package com.MoleLaw_backend.service.law;

import com.MoleLaw_backend.dto.response.KeywordAndTitleResponse;
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

    public KeywordAndTitleResponse extractKeywords(String userInput) {
        try {
            String prompt = String.format("""
다음 문장에서 법적 판례 검색에 유용한 **법령 중심의 키워드** 3~6개와, 전체 문장의 요약, 관련 소관부처명을 추출하여 아래 JSON 형식으로 출력하세요.

- 키워드는 일반 단어보다는 실제 법령 이름이나, 법령 본문에 등장할 수 있는 진부한 표현 위주로 작성할 것 (예: "해고", "권리 침해" → "근로기준법", "근로계약 해지", "해고예고제")
- 법령 제목 검색과 본문 검색에 모두 잘 대응되도록 단어를 구성할 것
- 소관부처는 최대한 정확히 명시하고, 불명확하면 "기타"로 표기

예시:
{
  "keywords": ["근로기준법", "근로계약 해지", "해고예고제", "부당해고", "근로자 권리"],
  "summary": "부당해고로 인한 근로자의 권리 침해에 대한 사안입니다.",
  "ministry": "고용노동부"
}

문장: "%s"
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

            String content = (String) ((Map<String, Object>)
                    ((Map<String, Object>)
                            ((List<?>) body.get("choices")).get(0)
                    ).get("message")
            ).get("content");
            return objectMapper.readValue(content, KeywordAndTitleResponse.class);

        } catch (Exception e) {
            throw new GptApiException(ErrorCode.GPT_API_FAILURE, "GPT 키워드 추출 실패", e);
        }
    }

}
