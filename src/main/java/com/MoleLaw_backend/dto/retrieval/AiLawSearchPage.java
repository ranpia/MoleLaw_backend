package com.MoleLaw_backend.dto.retrieval;

import java.util.List;
import com.MoleLaw_backend.dto.evidence.EvidenceItem;

public record AiLawSearchPage(int page, int display, int totalCount, boolean hasNext, List<EvidenceItem> evidence) {
    public AiLawSearchPage { evidence = List.copyOf(evidence); }
}
