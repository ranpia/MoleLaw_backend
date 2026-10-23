package com.MoleLaw_backend.law.config;

import org.springframework.ai.chat.client.ChatClient;
import com.MoleLaw_backend.common.ai.config.ReasoningOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import com.MoleLaw_backend.law.dto.retrieval.EvidenceDecision;
import org.springframework.ai.converter.BeanOutputConverter;

/** Evidence planning only. Execution context, tool authorization and loop termination belong to the server. */
@Configuration(proxyBeanMethods = false)
public class EvidenceChatClientConfig {
    @Bean
    public ChatClient evidenceReadChatClient(
            @Qualifier("baseAiChatClientBuilder") ChatClient.Builder builder,
            @Value("${app.ai.read.model:gpt-5.4-mini}") String model,
            @Value("${app.ai.read.reasoning-effort:low}") String effort,
            @Value("${app.ai.read.max-completion-tokens:4096}") int maxTokens,
            @Value("classpath:prompts/evidence-reading.txt") Resource prompt) throws IOException {
        return builder.clone().defaultSystem(prompt.getContentAsString(StandardCharsets.UTF_8)
                        + "\n" + new BeanOutputConverter<>(com.MoleLaw_backend.law.dto.retrieval.EvidenceReading.Selection.class).getFormat())
                .defaultOptions(ReasoningOptions.create(model, effort, maxTokens)).build();
    }
    @Bean
    public ChatClient lawCandidateChatClient(
            @Qualifier("baseAiChatClientBuilder") ChatClient.Builder builder,
            @Value("${app.ai.candidate.model:gpt-5.4-mini}") String model,
            @Value("${app.ai.candidate.reasoning-effort:low}") String effort,
            @Value("${app.ai.candidate.max-completion-tokens:8192}") int maxTokens,
            @Value("classpath:prompts/law-candidate-selection.txt") Resource prompt) throws IOException {
        return builder.clone().defaultSystem(prompt.getContentAsString(StandardCharsets.UTF_8)
                        + "\n" + new BeanOutputConverter<>(com.MoleLaw_backend.law.dto.retrieval.LawCandidateSelection.class).getFormat())
                .defaultOptions(ReasoningOptions.create(model, effort, maxTokens)).build();
    }

    @Bean
    public ChatClient questionPlanningChatClient(
            @Qualifier("baseAiChatClientBuilder") ChatClient.Builder builder,
            @Value("${app.ai.question.model:gpt-5.4-mini}") String model,
            @Value("${app.ai.question.reasoning-effort:low}") String effort,
            @Value("${app.ai.question.max-completion-tokens:4096}") int maxTokens,
            @Value("classpath:prompts/question-retrieval-plan.txt") Resource prompt) throws IOException {
        return builder.clone().defaultSystem(prompt.getContentAsString(StandardCharsets.UTF_8))
                .defaultOptions(ReasoningOptions.create(model, effort, maxTokens)).build();
    }

    @Bean
    public ChatClient evidenceCollectionChatClient(
            @Qualifier("baseAiChatClientBuilder") ChatClient.Builder builder,
            @Value("${app.ai.evidence.model:gpt-5.4}") String model,
            @Value("${app.ai.evidence.reasoning-effort:medium}") String effort,
            @Value("${app.ai.evidence.max-completion-tokens:12288}") int maxTokens,
            @Value("classpath:prompts/evidence-decision.txt") Resource decisionPrompt) throws IOException {
        return builder.clone()
                .defaultSystem(decisionPrompt.getContentAsString(StandardCharsets.UTF_8)
                        + "\n" + new BeanOutputConverter<>(EvidenceDecision.class).getFormat())
                .defaultOptions(ReasoningOptions.create(model, effort, maxTokens))
                .build();
    }
}
