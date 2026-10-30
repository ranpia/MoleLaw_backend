package com.MoleLaw_backend.law.util;

import com.MoleLaw_backend.law.dto.vector.LawSearchEvidence;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EvidenceSearchProgressTest {
    @Test void laterSupplementalQueriesDoNotEraseOriginalIssueAssociations() {
        var progress = new EvidenceSearchProgress(List.of("첫 쟁점", "둘째 쟁점"));
        progress.record("첫 쟁점", List.of(hit("123:1", "첫 법령 제1조")));
        progress.record("둘째 쟁점", List.of(hit("456:1", "둘째 법령 제1조")));
        progress.record("추가 검색", List.of(hit("123:2", "첫 법령 제2조")));
        assertThat(progress.groups()).containsExactly(List.of("123:1"), List.of("456:1"), List.of("123:2"));
        progress.record("둘째 쟁점", List.of(hit("456:2", "둘째 법령 제2조")));
        assertThat(progress.groups().get(1)).containsExactly("456:2");
    }

    private LawSearchEvidence hit(String unit, String title) {
        return new LawSearchEvidence(unit, "source", unit.split(":")[0], "law", "20260101", title,
                "청크", "본문", "https://www.law.go.kr", .8, "revision", "fingerprint", "generation");
    }
}
