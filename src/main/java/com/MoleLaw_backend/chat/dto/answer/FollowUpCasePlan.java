package com.MoleLaw_backend.chat.dto.answer;

/** Model decides necessity; server consent and room ownership still control execution. */
public record FollowUpCasePlan(Action action, String lawEvidenceId, String query, String reason) {
    public enum Action { ANSWER_FROM_ROOM, SEARCH_CASES }
    public FollowUpCasePlan {
        if (action == null || query == null || reason == null || reason.isBlank() || reason.length() > 300 || query.length() > 200)
            throw new IllegalArgumentException("Invalid follow-up case plan");
        if (action == Action.SEARCH_CASES && (lawEvidenceId == null || lawEvidenceId.isBlank() || query.isBlank()))
            throw new IllegalArgumentException("Search requires a room law and a query");
        if (action == Action.ANSWER_FROM_ROOM && (lawEvidenceId != null || !query.isEmpty()))
            throw new IllegalArgumentException("Room answer cannot carry a new search");
    }
}
