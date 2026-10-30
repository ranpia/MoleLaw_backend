package com.MoleLaw_backend.chat.dto.answer;

import com.MoleLaw_backend.law.dto.retrieval.CollectedLegalEvidence;

/** Internal stream states; collected source bodies are never serialized as progress payloads. */
public record InitialAnswerUpdate(String type, String text, CollectedLegalEvidence collected,
                                  PreparedLegalAnswer prepared, PreparedFollowUpAnswer followUp,
                                  java.util.List<com.MoleLaw_backend.law.dto.evidence.EvidenceItem> evidence) {
    public InitialAnswerUpdate(String type, String text, CollectedLegalEvidence collected, PreparedLegalAnswer prepared,
            PreparedFollowUpAnswer followUp) {
        this(type, text, collected, prepared, followUp, null);
    }
    public InitialAnswerUpdate(String type, String text, CollectedLegalEvidence collected, PreparedLegalAnswer prepared) {
        this(type, text, collected, prepared, null);
    }
    public static InitialAnswerUpdate completeFollowUp(PreparedFollowUpAnswer value) {
        return new InitialAnswerUpdate("completed", null, null, null, value);
    }
    public static InitialAnswerUpdate progress(String stage) { return new InitialAnswerUpdate("progress", stage, null, null); }
    public static InitialAnswerUpdate info(String markdown) { return new InitialAnswerUpdate("info", markdown, null, null); }
    public static InitialAnswerUpdate evidence(java.util.List<com.MoleLaw_backend.law.dto.evidence.EvidenceItem> items) {
        return new InitialAnswerUpdate("evidence", null, null, null, null, java.util.List.copyOf(items));
    }
    public static InitialAnswerUpdate delta(String text) { return new InitialAnswerUpdate("answer_delta", text, null, null); }
    public static InitialAnswerUpdate collected(CollectedLegalEvidence value) { return new InitialAnswerUpdate("collected", null, value, null); }
    public static InitialAnswerUpdate complete(PreparedLegalAnswer value) { return new InitialAnswerUpdate("completed", null, null, value); }
}
