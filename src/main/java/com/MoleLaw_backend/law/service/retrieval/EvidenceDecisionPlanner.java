package com.MoleLaw_backend.law.service.retrieval;

import com.MoleLaw_backend.law.dto.retrieval.EvidenceDecision;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Proposes operations without granting tool access, changing budgets or storing chat memory. */
@Service
public class EvidenceDecisionPlanner {
    private final ChatClient client;
    private final ObjectMapper mapper;

    public EvidenceDecisionPlanner(@Qualifier("evidenceCollectionChatClient") ChatClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    public EvidenceDecision decide(String question, List<EvidenceItem> evidence) {
        try {
            String input = mapper.writeValueAsString(Map.of("question", question, "evidence", evidence));
            String output = client.prompt().messages(List.of(new UserMessage(input))).call().content();
            return EvidenceDecision.parse(output, evidence.stream().map(EvidenceItem::id).toList(), mapper);
        } catch (JsonProcessingException | RuntimeException invalid) {
            // Do not leak provider errors or model output, and never retry a chargeable call here.
            throw new GptApiException(ErrorCode.GPT_API_FAILURE);
        }
    }
}
