package com.MoleLaw_backend.law.dto.retrieval;

import java.util.List;

/** Metadata selection grants local candidate IDs to fetch, never permission to cite. */
public record CaseReadSelection(List<String> candidateIds) {
    public CaseReadSelection {
        if (candidateIds == null || candidateIds.size() > 6
                || candidateIds.stream().anyMatch(id -> id == null || !id.matches("[0-9]{1,20}"))
                || candidateIds.stream().distinct().count() != candidateIds.size())
            throw new IllegalArgumentException("Invalid case read selection");
        candidateIds = List.copyOf(candidateIds);
    }
}
