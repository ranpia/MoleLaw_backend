package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;

/** Formats immutable evidence as data, without interpreting it as a prompt template. */
@Component
public class EvidencePromptBuilder {
    public String build(String question, List<EvidenceItem> evidence) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question must not be blank");
        }
        if (evidence == null || evidence.isEmpty()) {
            throw new GptApiException(ErrorCode.GPT_API_FAILURE);
        }
        StringBuilder prompt = new StringBuilder("서버가 상담방에 고정한 근거 자료:\n");
        for (EvidenceItem item : evidence) {
            prompt.append("[[").append(item.id()).append("]] ").append(item.kind()).append(' ')
                    .append(item.title()).append('\n');
            if (item.provenance() != null) {
                var provenance = item.provenance();
                appendObserved(prompt, "법령 MST", provenance.mst());
                appendObserved(prompt, "법령 ID", provenance.lawId());
                appendObserved(prompt, "원문에 표시된 시행일", provenance.effectiveDate());
                appendObserved(prompt, "조문 경로", provenance.articlePath());
            }
            prompt.append(item.content()).append("\n\n");
        }
        return prompt.append("현재 사용자 질문:\n").append(question).toString();
    }

    private void appendObserved(StringBuilder prompt, String label, String value) {
        if (value != null && !value.isBlank()) prompt.append(label).append(": ").append(value).append('\n');
    }
}
