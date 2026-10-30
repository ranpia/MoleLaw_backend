package com.MoleLaw_backend.chat.dto.answer;

/** Model decides necessity; server consent and room ownership still control execution. */
public record FollowUpCasePlan(Action action, String lawEvidenceId, String query, String reason) {
    public enum Action { ANSWER_FROM_ROOM, READ_ROOM_CASES, SEARCH_CASES }
    public FollowUpCasePlan {
        if (action == null || query == null || reason == null || reason.isBlank() || reason.length() > 300 || query.length() > 200)
            throw new IllegalArgumentException("Invalid follow-up case plan");
        if (action == Action.SEARCH_CASES && (query.isBlank() || lawEvidenceId != null && lawEvidenceId.isBlank()))
            throw new IllegalArgumentException("Search requires a query and an optional room law");
        if (action != Action.SEARCH_CASES && (lawEvidenceId != null || !query.isEmpty()))
            throw new IllegalArgumentException("Room answer cannot carry a new search");
    }
}
