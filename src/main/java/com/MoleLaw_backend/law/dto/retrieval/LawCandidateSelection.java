package com.MoleLaw_backend.law.dto.retrieval;

import java.util.HashSet;
import java.util.List;

/** Only identifiers from the server-supplied page may authorize detail collection. */
public record LawCandidateSelection(List<Ranking> rankings) {
    public LawCandidateSelection {
        if (rankings == null || rankings.size() > 20 || rankings.stream().anyMatch(item -> item == null)
                || new HashSet<>(rankings.stream().map(Ranking::candidateId).toList()).size() != rankings.size())
            throw new IllegalArgumentException("Invalid candidate selection");
        rankings = List.copyOf(rankings);
    }

    public record Ranking(String candidateId, Integer score, String reason) {
        public Ranking {
            if (candidateId == null || candidateId.isBlank() || candidateId.length() > 128
                    || score == null || score < 0 || score > 100
                    || reason == null || reason.isBlank() || reason.length() > 200)
                throw new IllegalArgumentException("Invalid candidate ranking");
        }
    }
}
