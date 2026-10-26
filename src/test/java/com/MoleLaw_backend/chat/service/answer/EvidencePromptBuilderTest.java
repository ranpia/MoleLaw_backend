package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.law.dto.evidence.EvidenceProvenance;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class EvidencePromptBuilderTest {
    private final EvidencePromptBuilder builder = new EvidencePromptBuilder();

    @Test void followUpKeepsCurrentCaseRequestInsteadOfForcingALawExplanationFirst() {
        var item = new EvidenceItem("law", "LAW_CHUNK", "1:48", "구조안전 규정", "https://www.law.go.kr", "법령 본문");
        assertThat(builder.buildFollowUp("지정한 사건의 판결을 찾아줘", List.of(item)))
                .contains("현재 사용자 요청에 맞춰", "일반 법령 설명으로 바꾸지", "동의 요청 도구", "지정한 사건의 판결을 찾아줘", "관련 판례는 별도로 구분")
                .doesNotContain("답변은 확인한 조문의 규정과 질문에 대한 설명으로 시작");
        assertThat(builder.buildFollowUpAnswer("지정한 사건의 판결", List.of(item)))
                .contains("현재 사용자 요청에 맞춰").doesNotContain("조문의 규정과 질문에 대한 설명으로 시작");
    }

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

    @Test void answerKeepsEveryRequestButDoesNotRepeatSourceQuotations() {
        var analysis = new com.MoleLaw_backend.law.dto.retrieval.QuestionAnalysis("복합 상담", "관계와 시점 설명", List.of(),
                List.of(new com.MoleLaw_backend.law.dto.retrieval.QuestionAnalysis.Issue("Q1", "두 기준의 관계",
                                "첫 번째 원문", List.of("기준 관계"), List.of(), List.of(), List.of()),
                        new com.MoleLaw_backend.law.dto.retrieval.QuestionAnalysis.Issue("Q2", "기존 대상에 대한 적용 시점",
                                "두 번째 원문", List.of("적용 시점"), List.of(), List.of(), List.of("변경 시점"))), List.of(), List.of());
        var plan = new com.MoleLaw_backend.law.dto.retrieval.QuestionRetrievalPlan(
                com.MoleLaw_backend.law.dto.retrieval.QuestionRetrievalPlan.Type.LAW_EXPLANATION,
                com.MoleLaw_backend.law.dto.retrieval.QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE,
                List.of("기준"), List.of(), List.of(), analysis);
        var item = new EvidenceItem("id", "LAW_CHUNK", "law:1", "법령", "https://www.law.go.kr", "본문");
        assertThat(builder.build("첫 번째 원문과 두 번째 원문을 설명해줘", List.of(item), plan))
                .contains("Q1: 두 기준의 관계", "Q2: 기존 대상에 대한 적용 시점", "변경 시점")
                .containsOnlyOnce("첫 번째 원문").containsOnlyOnce("두 번째 원문");
    }
}
