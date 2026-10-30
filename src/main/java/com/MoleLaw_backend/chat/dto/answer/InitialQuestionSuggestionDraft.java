package com.MoleLaw_backend.chat.dto.answer;

import java.util.List;
import java.util.Map;
import org.springframework.ai.openai.api.ResponseFormat;

/** Model proposes short request directions; the server preserves the original question. */
public record InitialQuestionSuggestionDraft(List<Proposal> suggestions) {
    public record Proposal(String label, String focus) { }

    public static ResponseFormat responseFormat() {
        var schema = Map.<String, Object>of("type", "object", "additionalProperties", false,
                "required", List.of("suggestions"), "properties", Map.of("suggestions", Map.of(
                        "type", "array", "items", Map.of("type", "object", "additionalProperties", false,
                                "required", List.of("label", "focus"), "properties", Map.of(
                                        "label", Map.of("type", "string"), "focus", Map.of("type", "string"))))));
        return ResponseFormat.builder().type(ResponseFormat.Type.JSON_SCHEMA)
                .jsonSchema(ResponseFormat.JsonSchema.builder().name("initial_question_suggestions").strict(true).schema(schema).build()).build();
    }
}
