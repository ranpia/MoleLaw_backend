package com.MoleLaw_backend.law.dto.retrieval;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import java.util.List;
import java.util.Objects;

public record CollectedLegalEvidence(QuestionRetrievalPlan plan, List<EvidenceItem> evidence, List<String> unresolvedIssues) {
    public CollectedLegalEvidence(QuestionRetrievalPlan plan, List<EvidenceItem> evidence) {
        this(plan, evidence, List.of());
    }
    public CollectedLegalEvidence {
        Objects.requireNonNull(plan);
        evidence = List.copyOf(evidence);
        unresolvedIssues = List.copyOf(unresolvedIssues);
        if (!unresolvedIssues.isEmpty() && (evidence.isEmpty()
                || plan.responseScope() != QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE))
            throw new IllegalArgumentException("Partial evidence requires grounded general guidance");
    }
}
