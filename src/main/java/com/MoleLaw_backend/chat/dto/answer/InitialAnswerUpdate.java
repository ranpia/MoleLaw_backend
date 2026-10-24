package com.MoleLaw_backend.chat.dto.answer;

import com.MoleLaw_backend.law.dto.retrieval.CollectedLegalEvidence;

/** Internal stream states; collected source bodies are never serialized as progress payloads. */
public record InitialAnswerUpdate(String type, String text, CollectedLegalEvidence collected,
                                  PreparedLegalAnswer prepared, PreparedFollowUpAnswer followUp) {
    public InitialAnswerUpdate(String type, String text, CollectedLegalEvidence collected, PreparedLegalAnswer prepared) {
        this(type, text, collected, prepared, null);
    }
    public static InitialAnswerUpdate completeFollowUp(PreparedFollowUpAnswer value) {
        return new InitialAnswerUpdate("completed", null, null, null, value);
    }
    public static InitialAnswerUpdate progress(String stage) { return new InitialAnswerUpdate("progress", stage, null, null); }
    public static InitialAnswerUpdate info(String markdown) { return new InitialAnswerUpdate("info", markdown, null, null); }
    public static InitialAnswerUpdate delta(String text) { return new InitialAnswerUpdate("answer_delta", text, null, null); }
    public static InitialAnswerUpdate collected(CollectedLegalEvidence value) { return new InitialAnswerUpdate("collected", null, value, null); }
    public static InitialAnswerUpdate complete(PreparedLegalAnswer value) { return new InitialAnswerUpdate("completed", null, null, value); }
}
