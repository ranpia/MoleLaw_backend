package com.MoleLaw_backend.law.dto.retrieval;

import java.util.List;
import com.MoleLaw_backend.law.util.LawSearchQueryKey;

public record CaseQueryPlan(List<String> queries, Intent intent, List<String> targets, CaseSearchScope scope) {
    public enum Intent { SPECIFIC_CASE, SIMILAR_ISSUE }
    public CaseQueryPlan(List<String> queries) { this(queries, Intent.SIMILAR_ISSUE, List.of(), null); }
    public CaseQueryPlan {
        if (intent == null || targets == null || targets.size() > 4 || targets.stream().anyMatch(t -> t == null || t.isBlank() || t.length() > 80))
            throw new IllegalArgumentException("Invalid case search intent");
        targets = targets.stream().map(String::strip).distinct().toList();
        if (intent == Intent.SPECIFIC_CASE && targets.isEmpty()) throw new IllegalArgumentException("Specific case needs observed targets");
        if (queries == null || queries.isEmpty() || queries.size() > 3 || queries.stream().anyMatch(q -> q == null || q.isBlank() || q.length() > 80))
            throw new IllegalArgumentException("Invalid case query plan");
        queries = queries.stream().map(String::strip).toList();
        if (queries.stream().map(LawSearchQueryKey::of).distinct().count() != queries.size())
            throw new IllegalArgumentException("Duplicate case query");
    }

    public void validateAgainst(String issue, String userQuestion) {
        String supplied = LawSearchQueryKey.of(issue + " " + (userQuestion == null ? "" : userQuestion));
        if (scope == null || targets.stream().anyMatch(target -> !supplied.contains(LawSearchQueryKey.of(target))))
            throw new IllegalArgumentException("Unobserved case target or missing scope");
        if (intent == Intent.SPECIFIC_CASE && targets.stream().noneMatch(target -> queries.stream()
                .anyMatch(query -> LawSearchQueryKey.of(query).contains(LawSearchQueryKey.of(target)))))
            throw new IllegalArgumentException("Specific case query lost its observed target");
    }
}
