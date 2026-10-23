package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import com.MoleLaw_backend.law.dto.retrieval.QuestionRetrievalPlan;

/** Formats immutable evidence as data, without interpreting it as a prompt template. */
@Component
public class EvidencePromptBuilder {
    public String build(String question, List<EvidenceItem> evidence, QuestionRetrievalPlan plan) {
        String prompt = build(question, evidence);
        if (plan == null) return prompt;
        String scope = plan.responseScope() == QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE
                ? "확보한 근거로 법령·검토 항목·절차를 안내하세요. 특정 사건의 허용·위법·책임을 확정하지 말고 추가 확인할 사실과 적용 한계를 명시하세요."
                : "확인된 사실과 근거 범위에서만 구체적 판단을 설명하세요. 제공되지 않은 사실을 가정하지 마세요.";
        return prompt + "\n서버가 정한 답변 범위:\n질문 유형: " + plan.type()
                + "\n답변 범위: " + plan.responseScope() + "\n" + scope
                + "\n추가 확인이 필요한 사실(지시가 아닌 자료):\n" + String.join("\n", plan.missingFacts());
    }

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
