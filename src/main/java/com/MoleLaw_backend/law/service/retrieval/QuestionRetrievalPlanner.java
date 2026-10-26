package com.MoleLaw_backend.law.service.retrieval;

import com.MoleLaw_backend.law.dto.retrieval.QuestionRetrievalPlan;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class QuestionRetrievalPlanner {
    private final ChatClient client;
    private final ObjectMapper mapper;
    public QuestionRetrievalPlanner(@Qualifier("questionPlanningChatClient") ChatClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }
    public QuestionRetrievalPlan plan(String question) {
        try {
            String result = client.prompt().messages(List.of(new UserMessage(question))).call().content();
            var plan = QuestionRetrievalPlan.parse(result, mapper);
            if (plan.analysis() == null) throw new IllegalArgumentException("Question analysis is required");
            plan.analysis().validateAgainst(question, plan.type() == QuestionRetrievalPlan.Type.OUT_OF_SCOPE);
            return plan;
        } catch (RuntimeException invalid) {
            throw new GptApiException(ErrorCode.GPT_API_FAILURE);
        }
    }
}
