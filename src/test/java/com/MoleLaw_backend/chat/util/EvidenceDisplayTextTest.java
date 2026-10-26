package com.MoleLaw_backend.chat.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EvidenceDisplayTextTest {
    @Test void rendersParagraphsBreaksAndEntitiesWithoutMarkupOrExecutableContent() {
        String source = "<p><b>판결 요지</b><br>책임&nbsp;범위 &amp; 판단</p><p>다음 문단</p><script>alert('hidden')</script>";
        assertThat(EvidenceDisplayText.plain(source)).isEqualTo("판결 요지\n책임 범위 & 판단\n다음 문단");
    }

    @Test void handlesEscapedMarkupAndPreservesPlainLegalTextAndComparisons() {
        assertThat(EvidenceDisplayText.plain("&lt;p&gt;주문&lt;br&gt;파기&lt;/p&gt;")).isEqualTo("주문\n파기");
        assertThat(EvidenceDisplayText.plain("&amp;lt;b&amp;gt;요지&amp;lt;/b&amp;gt;")).isEqualTo("요지");
        assertThat(EvidenceDisplayText.plain("제1조\n3 < 5이고 10 > 2이다.\n<계약> 내용")).isEqualTo("제1조\n3 < 5이고 10 > 2이다.\n<계약> 내용");
    }
}
