package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.chat.dto.response.GptAnswerResponse;
import com.MoleLaw_backend.chat.service.evidence.EvidenceItem;
import java.util.List;

/** Internal result: the exact immutable evidence used to produce the initial answer. */
public record PreparedLegalAnswer(GptAnswerResponse response, List<EvidenceItem> evidence) {
    public PreparedLegalAnswer { evidence = List.copyOf(evidence); }
}
