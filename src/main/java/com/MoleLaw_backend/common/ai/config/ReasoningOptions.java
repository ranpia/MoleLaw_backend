package com.MoleLaw_backend.common.ai.config;

import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.Set;

/** Completion limits include hidden reasoning tokens as well as visible output. */
public final class ReasoningOptions {
    private ReasoningOptions() { }

    public static OpenAiChatOptions create(String model, String effort, int maxCompletionTokens) {
        if (model == null || model.isBlank() || !Set.of("none", "low", "medium", "high").contains(effort)
                || maxCompletionTokens < 1) {
            throw new IllegalArgumentException("Invalid reasoning client settings");
        }
        return OpenAiChatOptions.builder().model(model).reasoningEffort(effort)
                .maxCompletionTokens(maxCompletionTokens).build();
    }
}
