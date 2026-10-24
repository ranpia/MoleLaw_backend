package com.MoleLaw_backend.chat.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StreamCitationDisplayTest {
    private static final String ID = "00000000-0000-0000-0000-000000000001";

    @Test void everyChunkBoundaryHidesRawCitationWhilePreservingOrdinaryMarkdown() {
        String text = "확인한 규정 [[" + ID + "]] 다음 [설명](주소)와 [표기]";
        for (int split = 0; split <= text.length(); split++) {
            var formatter = new StreamCitationDisplay();
            formatter.label(ID);
            String first = formatter.append(text.substring(0, split));
            assertThat(first).doesNotContain("00000000", "[[");
            String result = first + formatter.append(text.substring(split)) + formatter.finish();
            assertThat(result).isEqualTo("확인한 규정 [근거 1] 다음 [설명](주소)와 [표기]");
        }
    }

    @Test void singleCharacterChunksAndUnknownOrIncompleteCitationsNeverExposeIdentifiers() {
        var formatter = new StreamCitationDisplay();
        formatter.label(ID);
        String input = "[[" + ID + "]] [[unknown-id]] [[unfinished";
        StringBuilder shown = new StringBuilder();
        for (char ch : input.toCharArray()) {
            String delta = formatter.append(String.valueOf(ch));
            assertThat(delta).doesNotContain("0000", "unknown", "unfinished");
            shown.append(delta);
        }
        assertThat(shown.append(formatter.finish()).toString()).isEqualTo("[근거 1]  ");
    }

    @Test void hugeMalformedCitationIsSuppressedAndLabelsRemainStable() {
        var formatter = new StreamCitationDisplay();
        assertThat(formatter.label(ID)).isEqualTo("[근거 1]");
        assertThat(formatter.append("[[" + "x".repeat(10000) + "]]정상")).isEqualTo("정상");
        assertThat(formatter.label("second")).isEqualTo("[근거 2]");
        assertThat(formatter.label(ID)).isEqualTo("[근거 1]");
        assertThat(formatter.append("마지막 [") + formatter.finish()).isEqualTo("마지막 [");
    }
}
