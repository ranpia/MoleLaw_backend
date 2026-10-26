package com.MoleLaw_backend.law.util;

import com.MoleLaw_backend.law.dto.retrieval.EvidenceDecision.LawSearch;
import com.MoleLaw_backend.law.dto.retrieval.RetrievalAttempt;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Request-local queue. Only conditions approved by COLLECT_LAW can enter it. */
public final class LawCollectionQueue {
    private final Map<String, LawSearch> conditions = new LinkedHashMap<>();
    private final Map<String, RetrievalAttempt.Outcome> states = new LinkedHashMap<>();

    public static String key(LawSearch condition) {
        return LawSearchQueryKey.of(condition.query()) + "|" + condition.field() + "|"
                + condition.exactName() + "|" + condition.ministry();
    }

    public void offer(List<LawSearch> approved, List<RetrievalAttempt> feedback) {
        for (var condition : approved) {
            String key = key(condition);
            if (conditions.putIfAbsent(key, condition) == null)
                record(condition, RetrievalAttempt.Outcome.PENDING, feedback);
        }
    }

    public List<LawSearch> pending() {
        return conditions.entrySet().stream().filter(entry -> states.get(entry.getKey()) == RetrievalAttempt.Outcome.PENDING)
                .map(Map.Entry::getValue).toList();
    }

    public void record(LawSearch condition, RetrievalAttempt.Outcome outcome, List<RetrievalAttempt> feedback) {
        if (!conditions.containsKey(key(condition))) throw new IllegalArgumentException("Unapproved collection condition");
        states.put(key(condition), outcome);
        feedback.add(new RetrievalAttempt(RetrievalAttempt.Kind.LAW_COLLECTION, condition.query(), condition,
                null, outcome, 0, false));
    }
}
