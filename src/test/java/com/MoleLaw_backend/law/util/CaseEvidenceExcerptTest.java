package com.MoleLaw_backend.law.util;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import com.knuddels.jtokkit.api.EncodingType;
import static org.assertj.core.api.Assertions.assertThat;

class CaseEvidenceExcerptTest {
    private final JTokkitTokenCountEstimator tokens = new JTokkitTokenCountEstimator(EncodingType.CL100K_BASE);

    @Test void retainsOriginalEvidenceWhenItFits() {
        var original = item("짧은 판결 본문");
        assertThat(CaseEvidenceExcerpt.fit(original, 1000, List.of(), tokens::estimate)).isSameAs(original);
    }

    @Test void fitsJsonJudgmentToTokenBudgetAndIncludesTheTargetInTheMiddle() throws Exception {
        String judgment = "앞부분의 사실관계 설명. ".repeat(2000) + "게임 채팅 협박에 대한 판단" + "뒤쪽 판단 내용. ".repeat(2000);
        String json = new ObjectMapper().createObjectNode().put("사건번호", "2026도123")
                .put("판결요지", "메시지 내용에 관한 판단").put("판례내용", judgment).toString();
        var original = item(json);
        var visible = CaseEvidenceExcerpt.fit(original, 700, List.of("게임 채팅 협박"), tokens::estimate);
        assertThat(visible).isNotNull();
        assertThat(tokens.estimate(visible.content())).isLessThanOrEqualTo(700);
        assertThat(visible.content()).contains("판결문 일부 발췌", "게임 채팅 협박", "2026도123").doesNotContain(judgment);
        assertThat(visible.id()).isEqualTo(original.id());
        assertThat(visible.sourceId()).isEqualTo(original.sourceId());
        assertThat(original.content()).isEqualTo(json);
    }

    @Test void keepsUnicodeIntactAndCannotReadWithoutAnyBudget() {
        var original = item("게임😀의 협박 사건에 관한 판결. ".repeat(1000));
        var visible = CaseEvidenceExcerpt.fit(original, 300, List.of("협박"), tokens::estimate);
        assertThat(tokens.estimate(visible.content())).isLessThanOrEqualTo(300);
        assertThat(visible.content().codePoints().noneMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF)).isTrue();
        assertThat(CaseEvidenceExcerpt.fit(original, 0, List.of(), tokens::estimate)).isNull();
        assertThat(CaseEvidenceExcerpt.fit(original, 1, List.of(), tokens::estimate)).isNull();
    }

    private EvidenceItem item(String body) {
        return new EvidenceItem("original-id", "CASE_BODY", "123", "사건명", "https://www.law.go.kr", body);
    }
}
