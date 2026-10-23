package com.MoleLaw_backend.law.dto.retrieval;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.HashSet;
import java.util.List;

/** Catalog entries are discovery metadata; only read evidence can authorize an answer. */
public record EvidenceReading(ArrayNode catalog, List<EvidenceItem> evidence, List<Outcome> outcomes) {
    public EvidenceReading {
        catalog = catalog.deepCopy();
        evidence = List.copyOf(evidence);
        outcomes = List.copyOf(outcomes);
    }
    public record Selection(List<String> evidenceIds) {
        public Selection {
            if (evidenceIds == null || evidenceIds.size() > 8
                    || evidenceIds.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 128)
                    || new HashSet<>(evidenceIds).size() != evidenceIds.size())
                throw new IllegalArgumentException("Invalid evidence reading selection");
            evidenceIds = List.copyOf(evidenceIds);
        }
    }
    public enum Status { READ, BODY_BUDGET_EXCEEDED }
    public record Outcome(String evidenceId, Status status, int estimatedTokens) { }
}
