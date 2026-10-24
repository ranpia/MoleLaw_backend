package com.MoleLaw_backend.law.service.retrieval;

import com.MoleLaw_backend.law.dto.retrieval.CaseQueryPlan;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class CaseQueryPlanner {
    private final ChatClient client;
    private final ObjectMapper mapper;
    public CaseQueryPlanner(@Qualifier("caseQueryPlanningChatClient") ChatClient client, ObjectMapper mapper) {
        this.client = client; this.mapper = mapper;
    }
    public CaseQueryPlan plan(String issue, EvidenceItem law) {
        try {
            var input = mapper.createObjectNode().put("issue", issue).put("law", law.title())
                    .put("lawExcerpt", law.content().substring(0, Math.min(law.content().length(), 2000)));
            String output = client.prompt().user(mapper.writeValueAsString(input)).call().content();
            if (output == null || output.length() > 4096) throw new IllegalArgumentException();
            CaseQueryPlan result = mapper.readerFor(CaseQueryPlan.class).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).readValue(output);
            if (result == null) throw new IllegalArgumentException();
            return result;
        } catch (Exception failure) {
            throw new com.MoleLaw_backend.common.ai.exception.GptApiException(com.MoleLaw_backend.common.exception.ErrorCode.GPT_API_FAILURE);
        }
    }
}
