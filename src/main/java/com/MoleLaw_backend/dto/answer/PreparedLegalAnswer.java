package com.MoleLaw_backend.dto.answer;

import com.MoleLaw_backend.dto.response.LegalAnswerResponse;
import com.MoleLaw_backend.dto.evidence.EvidenceItem;
import java.util.List;

/** Internal result: the exact immutable evidence used to produce the initial answer. */
public record PreparedLegalAnswer(LegalAnswerResponse response, List<EvidenceItem> evidence, String title) {
    public PreparedLegalAnswer(LegalAnswerResponse response, List<EvidenceItem> evidence) { this(response, evidence, null); }
    public PreparedLegalAnswer { evidence = List.copyOf(evidence); }
}
