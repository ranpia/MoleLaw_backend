package com.MoleLaw_backend.law.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Evidence planning only. Execution context, tool authorization and loop termination belong to the server. */
@Configuration(proxyBeanMethods = false)
public class EvidenceChatClientConfig {
    @Bean
    public ChatClient evidenceCollectionChatClient(
            @Qualifier("baseAiChatClientBuilder") ChatClient.Builder builder,
            @Value("${app.ai.evidence.temperature:0.1}") double temperature,
            @Value("${app.ai.evidence.max-tokens:2048}") int maxTokens,
            @Value("classpath:prompts/evidence-decision.txt") Resource decisionPrompt) throws IOException {
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2 || maxTokens < 1) {
            throw new IllegalArgumentException("Invalid evidence client settings");
        }
        return builder.clone()
                .defaultSystem(decisionPrompt.getContentAsString(StandardCharsets.UTF_8))
                .defaultOptions(ChatOptions.builder().temperature(temperature).maxTokens(maxTokens).build())
                .build();
    }
}
