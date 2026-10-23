package com.MoleLaw_backend.law.dto.retrieval;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import java.util.List;
import java.util.Objects;

public record CollectedLegalEvidence(QuestionRetrievalPlan plan, List<EvidenceItem> evidence) {
    public CollectedLegalEvidence {
        Objects.requireNonNull(plan);
        evidence = List.copyOf(evidence);
    }
}
