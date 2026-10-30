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
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class QuestionRetrievalPlanner {
    private final ChatClient client;
    private final ObjectMapper mapper;
    public QuestionRetrievalPlanner(@Qualifier("questionPlanningChatClient") ChatClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }
    public QuestionRetrievalPlan plan(String question) {
        String stage = "MODEL_CALL";
        int outputLength = 0;
        long started = System.nanoTime();
        try {
            var response = client.prompt().messages(List.of(new UserMessage(question))).call().chatResponse();
            stage = "MODEL_RESPONSE";
            String result = response == null || response.getResult() == null
                    || response.getResult().getOutput() == null ? null : response.getResult().getOutput().getText();
            outputLength = result == null ? 0 : result.length();
            // Record only bounded provider metadata, never messages, response text or exception messages.
            String finish = response == null || response.getResult() == null
                    || response.getResult().getMetadata() == null ? null
                    : response.getResult().getMetadata().getFinishReason();
            String finishCategory = List.of("stop", "length", "content_filter", "tool_calls").contains(finish == null ? "" : finish)
                    ? finish : "OTHER";
            log.info("Question planning response outputLength={} finishCategory={} elapsedMs={}",
                    outputLength, finishCategory, (System.nanoTime() - started) / 1_000_000);
            // Parsing also executes record constructor contract validation.
            stage = "PARSE_OR_CONTRACT";
            var plan = QuestionRetrievalPlan.parse(result, mapper);
            stage = "ANALYSIS_REQUIRED";
            if (plan.analysis() == null) throw new IllegalArgumentException("Question analysis is required");
            stage = "SOURCE_GROUNDING";
            plan.analysis().validateAgainst(question, plan.type() == QuestionRetrievalPlan.Type.OUT_OF_SCOPE);
            return plan;
        } catch (RuntimeException invalid) {
            log.warn("Question planning failed stage={} errorType={} outputLength={} elapsedMs={}",
                    stage, invalid.getClass().getSimpleName(), outputLength,
                    (System.nanoTime() - started) / 1_000_000);
            throw new GptApiException(ErrorCode.GPT_API_FAILURE);
        }
    }
}
