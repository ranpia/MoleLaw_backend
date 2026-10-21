package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.chat.service.evidence.EvidenceItem;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class EvidencePromptBuilderTest {
    private final EvidencePromptBuilder builder = new EvidencePromptBuilder();

    @Test
    void preservesLiteralEvidenceAndAddsQuestionOnceWithoutMutatingInput() {
        EvidenceItem item = new EvidenceItem("evidence-id", "LAW_CHUNK", "law:1", "민법 {title}",
                "https://www.law.go.kr/법령/민법", "제1조 {content} [[literal]]");
        List<EvidenceItem> evidence = List.of(item);

        String result = builder.build("계약 해제가 가능한가요?", evidence);

        assertThat(result).isEqualTo("서버가 상담방에 고정한 근거 자료:\n"
                + "[[evidence-id]] LAW_CHUNK 민법 {title}\n제1조 {content} [[literal]]\n\n"
                + "현재 사용자 질문:\n계약 해제가 가능한가요?");
        assertThat(result).doesNotContain(item.sourceUrl());
        assertThat(evidence).containsExactly(item);
    }

    @Test
    void rejectsMissingQuestionOrEvidence() {
        assertThatThrownBy(() -> builder.build(" ", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build("질문", List.of())).isInstanceOf(GptApiException.class);
        assertThatThrownBy(() -> builder.build("질문", null)).isInstanceOf(GptApiException.class);
    }
}
