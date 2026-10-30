package com.MoleLaw_backend.law.util;

import com.MoleLaw_backend.law.dto.retrieval.QuestionRetrievalPlan;
import com.MoleLaw_backend.law.dto.retrieval.RetrievalAttempt;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Observed execution state only: a successful search is not proof that an issue is answered. */
public final class RetrievalProgressInput {
    private RetrievalProgressInput() { }

    public static ObjectNode build(QuestionRetrievalPlan plan, List<RetrievalAttempt> attempts, ObjectMapper mapper) {
        var result = mapper.createObjectNode();
        var issues = result.putArray("issueSearches");
        if (plan != null && plan.analysis() != null) {
            for (var issue : plan.analysis().issues()) {
                String query = LawSearchQueryKey.of(issue.searchTerms().get(0));
                var matches = attempts.stream().filter(attempt -> attempt.kind() == RetrievalAttempt.Kind.VECTOR_SEARCH
                        || attempt.kind() == RetrievalAttempt.Kind.AI_SEARCH)
                        .filter(attempt -> LawSearchQueryKey.of(attempt.query()).equals(query)
                                && attempt.outcome() != RetrievalAttempt.Outcome.DUPLICATE).toList();
                var item = issues.addObject().put("issueId", issue.id()).put("searched", !matches.isEmpty());
                if (!matches.isEmpty()) {
                    var latest = matches.get(matches.size() - 1);
                    item.put("outcome", latest.outcome().name()).put("candidateCount", latest.resultCount());
                }
            }
        }
        Map<String, RetrievalAttempt> states = new LinkedHashMap<>();
        attempts.stream().filter(attempt -> attempt.kind() == RetrievalAttempt.Kind.LAW_COLLECTION)
                .forEach(attempt -> states.put(LawCollectionQueue.key(attempt.conditions()), attempt));
        var queue = result.putArray("lawCollection");
        states.values().forEach(attempt -> {
            var item = queue.addObject().put("state", attempt.outcome().name());
            item.set("conditions", mapper.valueToTree(attempt.conditions()));
        });
        result.put("candidatePresenceIsSufficiency", false);
        return result;
    }
}
