package com.MoleLaw_backend.law.dto.retrieval;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.law.dto.tool.LegalToolContracts.Candidate;
import java.util.List;
import java.util.Map;

public record CaseSearchOutcome(String status, List<Candidate> candidates, Map<String, EvidenceItem> bodies,
        List<LawCandidateSelection.Ranking> rankings, int lawCount, int factCount, int uniqueCount,
        boolean lawUnavailable, boolean factUnavailable, boolean hasNext, boolean searchComplete) {
    public CaseSearchOutcome(String status, List<Candidate> candidates, Map<String, EvidenceItem> bodies,
            List<LawCandidateSelection.Ranking> rankings, int lawCount, int factCount, int uniqueCount,
            boolean lawUnavailable, boolean factUnavailable, boolean hasNext) {
        this(status, candidates, bodies, rankings, lawCount, factCount, uniqueCount, lawUnavailable, factUnavailable, hasNext, true);
    }
    public CaseSearchOutcome withSearchComplete(boolean complete) {
        return new CaseSearchOutcome(status, candidates, bodies, rankings, lawCount, factCount, uniqueCount, lawUnavailable, factUnavailable, hasNext, complete);
    }
    public CaseSearchOutcome {
        candidates = List.copyOf(candidates);
        bodies = Map.copyOf(bodies);
        rankings = List.copyOf(rankings);
    }
}
