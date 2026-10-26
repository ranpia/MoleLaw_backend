package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.chat.dto.answer.FollowUpCasePlan;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Classifies the latest user message without conversation memory; room law IDs only bind execution. */
@Service
public class FollowUpCasePlanner {
    private final ChatClient client;
    private final ObjectMapper mapper;
    public FollowUpCasePlanner(@Qualifier("followUpCasePlanningChatClient") ChatClient client, ObjectMapper mapper) {
        this.client = client; this.mapper = mapper;
    }

    public FollowUpCasePlan plan(String question, List<EvidenceItem> sources, String conversationId) {
        try {
            var input = mapper.createObjectNode().put("question", question);
            var laws = input.putArray("roomLaws");
            var allowed = sources.stream().filter(item -> item.kind().startsWith("LAW_")).map(EvidenceItem::id).toList();
            for (var item : sources) {
                if (item.kind().startsWith("LAW_")) laws.addObject().put("id", item.id()).put("title", item.title());
            }
            var anchorValues = new java.util.ArrayList<Object>(allowed); anchorValues.add(null);
            var schema = Map.<String, Object>of("type", "object", "additionalProperties", false,
                    "required", List.of("action", "lawEvidenceId", "query", "reason"), "properties", Map.of(
                            "action", Map.of("type", "string", "enum", List.of("ANSWER_FROM_ROOM", "SEARCH_CASES")),
                            "lawEvidenceId", Map.of("type", List.of("string", "null"), "enum", anchorValues),
                            "query", Map.of("type", "string"), "reason", Map.of("type", "string")));
            var format = ResponseFormat.builder().type(ResponseFormat.Type.JSON_SCHEMA)
                    .jsonSchema(ResponseFormat.JsonSchema.builder().name("follow_up_case_plan").strict(true).schema(schema).build()).build();
            String output = client.prompt().user(input.toString())
                    .options(OpenAiChatOptions.builder().responseFormat(format).build()).call().content();
            if (output == null || output.length() > 4096) throw new IllegalArgumentException();
            var plan = mapper.readerFor(FollowUpCasePlan.class).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).with(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                    .<FollowUpCasePlan>readValue(output);
            if (plan == null || (plan.action() == FollowUpCasePlan.Action.SEARCH_CASES && !allowed.contains(plan.lawEvidenceId())))
                throw new IllegalArgumentException();
            return plan;
        } catch (Exception failure) { throw new GptApiException(ErrorCode.GPT_API_FAILURE); }
    }
}
