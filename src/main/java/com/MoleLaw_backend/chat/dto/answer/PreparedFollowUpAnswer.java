package com.MoleLaw_backend.chat.dto.answer;

import com.MoleLaw_backend.chat.dto.response.LegalAnswerResponse;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import java.util.List;

/** Successful follow-up turn plus only the newly cited precedents and explicit consent state. */
public record PreparedFollowUpAnswer(LegalAnswerResponse response, List<EvidenceItem> newEvidence,
                                    boolean caseSearchRequested, boolean caseSearchConsent) {
    public PreparedFollowUpAnswer {
        newEvidence = List.copyOf(newEvidence);
        if ((!caseSearchConsent && !newEvidence.isEmpty()) || (caseSearchConsent && caseSearchRequested)
                || newEvidence.size() > 2 || newEvidence.stream().anyMatch(item -> !"CASE_BODY".equals(item.kind())))
            throw new IllegalArgumentException("Invalid follow-up consent or evidence result");
    }
}
