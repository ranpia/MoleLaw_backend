package com.MoleLaw_backend.law.service.retrieval;

import com.MoleLaw_backend.law.dto.retrieval.CaseQueryPlan;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import java.util.List;
import java.util.Map;

@Service
@lombok.extern.slf4j.Slf4j
public class CaseQueryPlanner {
    private final ChatClient client;
    private final ObjectMapper mapper;
    public CaseQueryPlanner(@Qualifier("caseQueryPlanningChatClient") ChatClient client, ObjectMapper mapper) {
        this.client = client; this.mapper = mapper;
    }
    public CaseQueryPlan plan(String issue, EvidenceItem law) {
        return plan(issue, law, null, null);
    }

    public CaseQueryPlan plan(String issue, EvidenceItem law, String userQuestion,
            com.MoleLaw_backend.law.dto.retrieval.CaseSearchScope requestedScope) {
        String validationCode = "FORMAT";
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                var input = mapper.createObjectNode().put("issue", issue).put("roomLawContext", law.title());
                input.put("inputContract", "issue는 대화에서 해소한 현재 검색 요청이며 userQuestion은 최신 발화 원문입니다. 동의 발화는 issue의 검색 목적·대상을 변경하지 않습니다.");
                input.put("userQuestion", userQuestion).put("requestedScope", requestedScope == null ? "BODY" : requestedScope.name());
                if (attempt > 0) input.put("repair", "이전 계획 검증 실패: " + validationCode
                        + ". 사용자 질문 또는 issue에 실제 있는 식별어만 targets에 그대로 복사하세요."
                        + " 대상 사건을 유지하고 서로 다른 1~3개 짧은 질의와 scope를 모두 제공하세요.");
                var strings = Map.of("type", "array", "items", Map.of("type", "string"));
                var schema = Map.<String, Object>of("type", "object", "additionalProperties", false,
                        "required", List.of("queries", "intent", "targets", "scope"), "properties", Map.of(
                                "queries", strings, "targets", strings,
                                "intent", Map.of("type", "string", "enum", List.of("SPECIFIC_CASE", "SIMILAR_ISSUE")),
                                "scope", Map.of("type", "string", "enum", List.of("BODY", "TITLE", "LAW_REFERENCE"))));
                var format = ResponseFormat.builder().type(ResponseFormat.Type.JSON_SCHEMA)
                        .jsonSchema(ResponseFormat.JsonSchema.builder().name("case_query_plan").strict(true).schema(schema).build()).build();
                validationCode = "MODEL_CALL";
                String output = client.prompt().user(mapper.writeValueAsString(input))
                        .options(OpenAiChatOptions.builder().responseFormat(format).build()).call().content();
                validationCode = "PLAN_CONTRACT";
                if (output == null || output.length() > 4096) throw new IllegalArgumentException();
                CaseQueryPlan result = mapper.readerFor(CaseQueryPlan.class).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).readValue(output);
                if (result == null) throw new IllegalArgumentException();
                validationCode = "TARGET_GROUNDING";
                result.validateAgainst(issue, userQuestion);
                return result;
            } catch (Exception failure) {
                log.warn("Case query planning rejected stage={} attempt={} errorType={}", validationCode, attempt + 1, failure.getClass().getSimpleName());
                if ("MODEL_CALL".equals(validationCode)) break;
            }
        }
        throw new com.MoleLaw_backend.common.ai.exception.GptApiException(com.MoleLaw_backend.common.exception.ErrorCode.GPT_API_FAILURE);
    }
}
