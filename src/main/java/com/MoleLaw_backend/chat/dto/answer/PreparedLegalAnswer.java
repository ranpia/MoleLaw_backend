package com.MoleLaw_backend.chat.dto.answer;

import com.MoleLaw_backend.chat.dto.response.LegalAnswerResponse;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import java.util.List;

/** Internal result: the exact immutable evidence used to produce the initial answer. */
public record PreparedLegalAnswer(LegalAnswerResponse response, List<EvidenceItem> evidence) {
    public PreparedLegalAnswer { evidence = List.copyOf(evidence); }
}
