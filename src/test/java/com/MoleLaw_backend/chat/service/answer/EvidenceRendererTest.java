package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class EvidenceRendererTest {
    @Test void regularAndStreamInfoUseTheSamePlainCaseSummaryWithoutChangingRawSource() {
        String raw = "{\"판결요지\":\"&lt;b&gt;요지&lt;/b&gt;&lt;br&gt;판단&nbsp;내용\",\"판례내용\":\"전체 본문\"}";
        var item = new EvidenceItem("id", "CASE_BODY", "101", "<b>사건</b>", "https://www.law.go.kr", raw);
        for (String shown : List.of(renderer.render(List.of(item)), renderer.renderForStream(List.of(item), id -> "근거 1")))
            assertThat(shown).contains("요지\n판단 내용", "사건").doesNotContain("<b>", "&lt;", "&nbsp;", "판례내용", "전체 본문");
        assertThat(item.content()).isEqualTo(raw);
    }
    private final EvidenceRenderer renderer = new EvidenceRenderer();

    @Test void streamingCaseInfoShowsJudgmentSummaryInsteadOfRawJsonKeysOrCitationIds() {
        String raw = "{\"판결요지\":\"휴가 사용 촉진에 관한 판단\",\"판례내용\":\"판결 전체 본문\",\"internalKey\":\"not-for-display\"}";
        var item = new EvidenceItem("internal-citation-uuid", "CASE_BODY", "101", "임금",
                "https://www.law.go.kr/precInfoP.do?precSeq=101", raw);
        String shown = renderer.renderForStream(List.of(item), id -> "[근거 1]");
        assertThat(shown).contains("[근거 1]", "휴가 사용 촉진에 관한 판단", "precSeq=101")
                .doesNotContain("internalKey", "internal-citation-uuid", "not-for-display", "판결 전체 본문");
        assertThat(item.content()).isEqualTo(raw);
    }

    @Test
    void rendersOnlyServerEvidenceAndEscapesExternalTitle() {
        EvidenceItem item = item("민법](https://attacker.example)\n[제1조]", "https://www.law.go.kr/law(a)");
        List<EvidenceItem> evidence = List.of(item);

        String result = renderer.render(evidence);

        assertThat(result).isEqualTo("## 고정 근거\n\n- [[id]] [민법\\]\\(https://attacker.example\\) \\[제1조\\]]"
                + "(https://www.law.go.kr/law%28a%29)\n  - 본문 {literal}\n");
        assertThat(evidence).containsExactly(item);
    }

    @Test
    void encodesUnicodeUrlWithoutDoubleEncodingExistingEscapes() {
        assertThat(renderer.render(List.of(item("민법", "https://www.law.go.kr/법령/민법?q=%20"))))
                .contains("https://www.law.go.kr/%EB%B2%95%EB%A0%B9/%EB%AF%BC%EB%B2%95?q=%20");
    }

    @Test
    void convertsLegacyMstLinksToPublicLawViewerWithoutChangingEvidence() {
        for (String path : List.of("법령", "%EB%B2%95%EB%A0%B9")) {
            EvidenceItem saved = item("근로기준법", "https://www.law.go.kr/" + path + "?lsId=001872&lsiSeq=270551");
            assertThat(renderer.render(List.of(saved)))
                    .contains("](https://www.law.go.kr/lsInfoP.do?lsiSeq=270551)");
            assertThat(saved.sourceUrl()).contains(path);
        }
    }

    @Test
    void rejectsUnsafeOrMalformedSourceUrls() {
        for (String url : List.of("javascript:alert(1)", "https://law.go.kr/a\n[evil]", "https://user@law.go.kr/a")) {
            assertThatThrownBy(() -> renderer.render(List.of(item("법령", url))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private EvidenceItem item(String title, String url) {
        return new EvidenceItem("id", "LAW_CHUNK", "law:1", title, url, "본문 {literal}");
    }
}
