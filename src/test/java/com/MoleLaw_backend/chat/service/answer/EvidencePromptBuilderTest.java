package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.law.dto.evidence.EvidenceProvenance;
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

        assertThat(result).endsWith("서버가 상담방에 고정한 근거 자료:\n"
                + "[[evidence-id]] LAW_CHUNK 민법 {title}\n제1조 {content} [[literal]]\n\n"
                + "현재 사용자 질문:\n계약 해제가 가능한가요?");
        assertThat(result).containsOnlyOnce("계약 해제가 가능한가요?")
                .contains("확보된 본문을 아직 확인하지 않은 것처럼 표현하지 마세요");
        assertThat(result).doesNotContain(item.sourceUrl());
        assertThat(evidence).containsExactly(item);
    }

    @Test
    void rejectsMissingQuestionOrEvidence() {
        assertThatThrownBy(() -> builder.build(" ", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build("질문", List.of())).isInstanceOf(GptApiException.class);
        assertThatThrownBy(() -> builder.build("질문", null)).isInstanceOf(GptApiException.class);
    }

    @Test
    void includesObservedLegalVersionWithoutPresentingIndexMetadataAsLawFacts() {
        var provenance = new EvidenceProvenance("source-uuid", "123456", "001706", "20260101",
                "제548조/제1항", "model-revision", "index-fingerprint", "index-generation");
        var item = new EvidenceItem("evidence-id", "LAW_QDRANT", "123456:제548조/제1항", "민법",
                "https://www.law.go.kr", "확보한 본문", provenance);
        assertThat(builder.build("질문", List.of(item)))
                .contains("법령 MST: 123456", "법령 ID: 001706", "원문에 표시된 시행일: 20260101", "조문 경로: 제548조/제1항")
                .doesNotContain("model-revision", "index-fingerprint", "index-generation", item.sourceUrl());
    }

    @Test
    void doesNotInventAnEffectiveDateForUnknownSourceMetadata() {
        var item = new EvidenceItem("evidence-id", "LAW_CHUNK", "law:1", "민법", "https://www.law.go.kr", "본문",
                new EvidenceProvenance(null, null, null, null, "제1조", null, null, null));
        assertThat(builder.build("질문", List.of(item)))
                .contains("조문 경로: 제1조").doesNotContain("시행일", "법령 MST", "null");
    }
}
