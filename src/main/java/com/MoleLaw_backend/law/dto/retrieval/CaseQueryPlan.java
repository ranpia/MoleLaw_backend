package com.MoleLaw_backend.law.dto.retrieval;

import java.util.List;
import com.MoleLaw_backend.law.util.LawSearchQueryKey;

public record CaseQueryPlan(List<String> queries) {
    public CaseQueryPlan {
        if (queries == null || queries.isEmpty() || queries.size() > 3 || queries.stream().anyMatch(q -> q == null || q.isBlank() || q.length() > 80))
            throw new IllegalArgumentException("Invalid case query plan");
        queries = queries.stream().map(String::strip).toList();
        if (queries.stream().map(LawSearchQueryKey::of).distinct().count() != queries.size())
            throw new IllegalArgumentException("Duplicate case query");
    }
}
