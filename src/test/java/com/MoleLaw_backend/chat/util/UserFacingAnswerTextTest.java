package com.MoleLaw_backend.chat.util;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class UserFacingAnswerTextTest {
    @Test void internalCodesAreTranslatedAcrossEveryStreamingBoundaryWithoutChangingCitations() {
        String text = "결과는 EMPTY. NO_RELEVANT_CASES UNAVAILABLE [[00000000-0000-0000-0000-000000000001]]";
        for (int split = 0; split <= text.length(); split++) {
            var filter = new UserFacingAnswerText();
            String shown = filter.append(text.substring(0, split)) + filter.append(text.substring(split)) + filter.finish();
            assertThat(shown).isEqualTo(UserFacingAnswerText.normalize(text)).doesNotContain("EMPTY", "NO_RELEVANT_CASES", "UNAVAILABLE");
        }
    }
}
