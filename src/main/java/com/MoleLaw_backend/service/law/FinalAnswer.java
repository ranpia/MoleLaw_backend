package com.MoleLaw_backend.service.law;

import com.MoleLaw_backend.dto.PrecedentInfo;
import com.MoleLaw_backend.dto.request.PrecedentSearchRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

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
                e.printStackTrace();
            }
        }

        // 3. 판례 검색
        List<PrecedentInfo> precedentResults = new ArrayList<>();
        for (String lawName : uniqueLawNames) {
            PrecedentSearchRequest req = new PrecedentSearchRequest();
            req.setQuery(lawName);
            try {
                List<PrecedentInfo> precedents = caseSearchService.searchCases(req);
                precedentResults.addAll(precedents);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 4. GPT 응답 생성 프롬프트 구성
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

        // 5. GPT API 호출
        Map<String, Object> requestBody = Map.of(
                "model", "gpt-4",
                "messages", List.of(
                        Map.of("role", "system", "content", "당신은 법에 관련하여 도움이 필요한 이용자를 도와주는 입장의 법률 전문가이며, 법령과 판례를 참고하여 일반 사용자의 질문에 친절하고 명확하게 답변합니다. 하단에는 마크다운 형식으로 관련 법령과 판례를 정리합니다. 또한 유저에게 대화하는 부분과 정보를 제공하는 부분을 구분자를 두어 분리합니다. 정보 제공 부분에는 시도가능한 해결책, 관련 법령(예시 : 무슨무슨 법 몇조), 판례를 제공합니다."),
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
            if (body == null) return "GPT 응답을 가져올 수 없습니다.";

            List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            return (String) message.get("content");

        } catch (Exception e) {
            e.printStackTrace();
            return "GPT 응답 생성 중 오류가 발생했습니다.";
        }
    }
}
