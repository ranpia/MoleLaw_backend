package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.chat.service.evidence.EvidenceItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class EvidenceRendererTest {
    private final EvidenceRenderer renderer = new EvidenceRenderer();

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
