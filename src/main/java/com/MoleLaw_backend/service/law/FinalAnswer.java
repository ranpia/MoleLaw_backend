package com.MoleLaw_backend.service.law;

import com.MoleLaw_backend.dto.PrecedentInfo;
import com.MoleLaw_backend.dto.request.PrecedentSearchRequest;
import com.MoleLaw_backend.exception.ErrorCode;
import com.MoleLaw_backend.exception.GptApiException;
import com.MoleLaw_backend.exception.OpenLawApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.*;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FinalAnswer {

    private final ExtractKeyword extractKeyword;
    private final LawSearchService lawSearchService;
    private final CaseSearchService caseSearchService;
    private final ObjectMapper objectMapper;

    @Value("${openai.api-key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    public String getFinalAnswer(String query) {
        // 1. 키워드 추출
        List<String> keywords = extractKeyword.extractKeywords(query);

        // 2. 법령 검색
        List<Map<String, Object>> lawResults = new ArrayList<>();
        Set<String> uniqueLawNames = new LinkedHashSet<>();
        for (String keyword : keywords) {
            try {
                String rawResult = lawSearchService.searchLawByKeyword(keyword);
                JsonNode root = objectMapper.readTree(rawResult);
                JsonNode lawArray = root.path("LawSearch").path("law");

                if (lawArray.isArray()) {
                    for (JsonNode law : lawArray) {
                        String name = law.path("법령명한글").asText();
                        if (uniqueLawNames.size() < 5 && uniqueLawNames.add(name)) {
                            Map<String, Object> lawItem = objectMapper.convertValue(law, new TypeReference<>() {});
                            lawResults.add(lawItem);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("[LawSearch] 키워드 '{}' 처리 중 오류: {}", keyword, e.getMessage(), e);
                // 검색 실패해도 무시하고 다음 키워드로 진행
            }
        }

        // 3. 판례 검색
        List<PrecedentInfo> precedentResults = new ArrayList<>();
        for (String lawName : uniqueLawNames) {
            try {
                PrecedentSearchRequest req = new PrecedentSearchRequest();
                req.setQuery(lawName);
                List<PrecedentInfo> precedents = caseSearchService.searchCases(req);
                precedentResults.addAll(precedents);
            } catch (OpenLawApiException e) {
                log.warn("[CaseSearch] 법령 '{}' 기반 판례 조회 실패: {}", lawName, e.getMessage());
                // 특정 판례 실패는 무시하고 전체 응답 생성 진행
            }
        }

        // 4. 프롬프트 구성
        StringBuilder prompt = new StringBuilder();
        prompt.append("사용자 질문: ").append(query).append("\n\n");
        prompt.append("다음은 관련 법령 및 판례 정보입니다. 이를 참고해 사용자 질문에 대해 명확하고 친절하게 답변해주세요.\n\n");

        prompt.append("### 관련 법령\n");
        for (Map<String, Object> law : lawResults) {
            prompt.append("- ").append(law.get("법령명한글")).append("\n");
        }

        prompt.append("\n### 관련 판례\n");
        for (PrecedentInfo p : precedentResults) {
            prompt.append("- ").append(p.getCaseNumber()).append(": ").append(p.getCaseName()).append(" (")
                    .append(p.getDecisionDate()).append(", ").append(p.getCourtName()).append(")\n");
        }

        // 5. GPT API 요청
        Map<String, Object> requestBody = Map.of(
                "model", "gpt-4",
                "messages", List.of(
                        Map.of("role", "system", "content", "당신은 법률 전문가입니다. 관련 법령 및 판례를 참고하여 사용자 질문에 대해 친절하고 명확하게 답변해주세요."),
                        Map.of("role", "user", "content", prompt.toString())
                ),
                "temperature", 0.5
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    "https://api.openai.com/v1/chat/completions",
                    entity,
                    Map.class
            );

            Map<String, Object> body = response.getBody();
            if (body == null || body.get("choices") == null) {
                throw new GptApiException(ErrorCode.GPT_EMPTY_RESPONSE, "OpenAI 응답이 비어 있음");
            }

            List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            return (String) message.get("content");

        } catch (Exception e) {
            log.error("[GPT] 응답 생성 실패: {}", e.getMessage(), e);
            throw new GptApiException(ErrorCode.GPT_API_FAILURE, e);
        }
    }
}
