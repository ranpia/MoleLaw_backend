package com.MoleLaw_backend.law.dto.retrieval;

/** Server-observed feedback. An API failure is propagated, never recorded as empty results. */
public record RetrievalAttempt(Kind kind, String query, EvidenceDecision.LawSearch conditions,
        Integer page, Outcome outcome, int resultCount, boolean hasNext) {
    public enum Kind { VECTOR_SEARCH, LAW_SEARCH, AI_SEARCH }
    public enum Outcome { AVAILABLE, NO_RESULTS, NO_EXACT_MATCH, NO_NEW_EVIDENCE, NO_RELEVANT_CANDIDATES, EXHAUSTED, DUPLICATE }
}
