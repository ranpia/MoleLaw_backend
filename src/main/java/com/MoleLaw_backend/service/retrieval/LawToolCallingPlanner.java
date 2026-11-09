package com.MoleLaw_backend.service.retrieval;

import com.MoleLaw_backend.dto.retrieval.CollectedLegalEvidence;
import com.MoleLaw_backend.dto.retrieval.EvidenceDecision;
import com.MoleLaw_backend.dto.retrieval.QuestionRetrievalPlan;
import com.MoleLaw_backend.dto.retrieval.RetrievalObserver;
import com.MoleLaw_backend.service.tool.LawRetrievalToolSession;
import com.MoleLaw_backend.util.QuestionPlanInput;
import com.MoleLaw_backend.util.exception.ErrorCode;
import com.MoleLaw_backend.util.exception.GptApiException;
import com.MoleLaw_backend.util.exception.MolelawException;
import com.MoleLaw_backend.util.exception.LegalToolException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knuddels.jtokkit.api.EncodingType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Actual model function calls, with server-owned limits and a body-verified terminal decision. */
@Slf4j
@Service
public class LawToolCallingPlanner {
    private final ChatClient client;
    private final ObjectMapper mapper;
    private final int maxSteps;
    private final int inputLimit;
    private final String prompt;
    private final JTokkitTokenCountEstimator tokens = new JTokkitTokenCountEstimator(EncodingType.CL100K_BASE);

    public LawToolCallingPlanner(@Qualifier("evidenceCollectionChatClient") ChatClient client, ObjectMapper mapper,
            @Value("${app.law-retrieval.max-tool-steps:12}") int maxSteps,
            @Value("${app.ai.evidence.decision-max-input-tokens:32000}") int inputLimit,
            @Value("classpath:prompts/law-retrieval-tools.txt") Resource prompt) throws IOException {
        if (maxSteps < 1 || maxSteps > 32 || inputLimit < 1024 || inputLimit > 128000)
            throw new IllegalArgumentException("Invalid law tool limits");
        this.client = client; this.mapper = mapper; this.maxSteps = maxSteps; this.inputLimit = inputLimit;
        var schema = mapper.readTree(new BeanOutputConverter<>(EvidenceDecision.class).getJsonSchema());
        ((com.fasterxml.jackson.databind.node.ObjectNode)schema.path("properties").path("action"))
                .set("enum", mapper.valueToTree(List.of("ANSWER_READY", "ANSWER_PARTIAL", "INSUFFICIENT")));
        this.prompt = prompt.getContentAsString(StandardCharsets.UTF_8) + "\n최종 JSON 스키마:\n" + schema;
    }

    public CollectedLegalEvidence collect(String question, QuestionRetrievalPlan plan,
            LawRetrievalToolSession session, RetrievalObserver observer) {
        Map<String, ToolCallback> allowed = new LinkedHashMap<>();
        Arrays.stream(ToolCallbacks.from(session)).filter(callback -> !session.fixedIndexOnly()
                || !List.of("searchLaws", "collectLaw").contains(callback.getToolDefinition().name()))
                .forEach(callback -> allowed.put(callback.getToolDefinition().name(), callback));
        var input = mapper.createObjectNode().put("question", question).put("fixedIndexOnly", session.fixedIndexOnly());
        input.set("retrievalPlan", QuestionPlanInput.view(plan, mapper));
        input.set("suggestedQueries", mapper.valueToTree(plan.initialSearchQueries()));
        List<Message> history = new ArrayList<>(List.of(new UserMessage(input.toString())));
        int calls = 0;
        boolean requestedInitialSearch = false;
        for (int step = 0; step < maxSteps; step++) {
            session.checkActive();
            observer.progress("DECIDING");
            // Include source bodies, tool descriptions/schemas and the full transcript in the prompt budget.
            int cost = tokens.estimate(prompt + allowed.values().stream()
                    .map(c -> c.getToolDefinition().description() + c.getToolDefinition().inputSchema())
                    .reduce("", String::concat)) + 256;
            for (var message : history) {
                cost += 128 + tokens.estimate(message.getText() == null ? "" : message.getText());
                if (message instanceof ToolResponseMessage toolResponse)
                    for (var result : toolResponse.getResponses()) cost += tokens.estimate(result.responseData());
                if (message instanceof org.springframework.ai.chat.messages.AssistantMessage assistant)
                    for (var call : assistant.getToolCalls()) cost += tokens.estimate(call.arguments());
            }
            if (cost > inputLimit) throw new MolelawException(ErrorCode.EVIDENCE_INSUFFICIENT);
            var response = client.prompt().system(prompt + "\n남은 모델 호출: " + (maxSteps - step))
                    .messages(history).toolCallbacks(List.copyOf(allowed.values())).call().chatResponse();
            session.checkActive();
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null)
                throw new GptApiException(ErrorCode.GPT_EMPTY_RESPONSE);
            var output = response.getResult().getOutput();
            if (!output.hasToolCalls()) {
                EvidenceDecision decision;
                try {
                    decision = EvidenceDecision.parse(output.getText(), session.readIds(), mapper);
                } catch (com.MoleLaw_backend.util.exception.EvidenceDecisionValidationException invalid) {
                    log.warn("Law tool executionId={} finalValidation={}", session.executionId(), invalid.getCode());
                    throw new GptApiException(ErrorCode.GPT_API_FAILURE);
                }
                if (decision.action() == EvidenceDecision.Action.SEARCH_AGAIN || decision.action() == EvidenceDecision.Action.COLLECT_LAW)
                    throw new GptApiException(ErrorCode.GPT_API_FAILURE);
                if (calls == 0 && decision.action() == EvidenceDecision.Action.INSUFFICIENT && !requestedInitialSearch) {
                    requestedInitialSearch = true;
                    history.add(output);
                    history.add(new UserMessage("법률 범위 안의 질문입니다. 근거 부재를 추정하지 말고 제공된 검색 도구로 실제 결과를 확인하세요."));
                    continue;
                }
                return session.finish(decision);
            }
            if (step == maxSteps - 1 || output.getToolCalls().size() > 4 || calls + output.getToolCalls().size() > 32)
                throw new MolelawException(ErrorCode.EVIDENCE_INSUFFICIENT);
            var batchIds = new java.util.HashSet<String>();
            // Validate the entire batch before executing any write-capable tool.
            for (var call : output.getToolCalls()) {
                if (call.id() == null || call.id().isBlank() || !batchIds.add(call.id())
                        || !"function".equals(call.type()) || !allowed.containsKey(call.name())
                        || call.arguments() == null || call.arguments().length() > 32768)
                    throw new GptApiException(ErrorCode.GPT_API_FAILURE);
            }
            history.add(output); // Intermediate model text is preparation only; never a user answer delta.
            List<ToolResponseMessage.ToolResponse> results = new ArrayList<>();
            for (var call : output.getToolCalls()) {
                observer.progress("DECIDING");
                session.checkActive();
                log.info("Law tool executionId={} step={} tool={} call={}", session.executionId(), step, call.name(), ++calls);
                try {
                    String result = allowed.get(call.name()).call(call.arguments());
                    results.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result));
                } catch (RuntimeException failure) {
                    // Spring's callback adapter may wrap a domain failure; preserve its safe classification.
                    Throwable cause = failure;
                    while (cause != null) {
                        if (cause instanceof MolelawException business) throw business;
                        if (cause instanceof LegalToolException legal) throw legal;
                        if (cause instanceof java.util.concurrent.CancellationException cancelled) throw cancelled;
                        cause = cause.getCause();
                    }
                    throw new GptApiException(ErrorCode.GPT_API_FAILURE);
                }
            }
            history.add(new ToolResponseMessage(results));
        }
        throw new MolelawException(ErrorCode.EVIDENCE_INSUFFICIENT);
    }
}
