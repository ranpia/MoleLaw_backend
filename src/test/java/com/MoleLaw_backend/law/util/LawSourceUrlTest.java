package com.MoleLaw_backend.law.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LawSourceUrlTest {
    @Test void detailUsesValidatedMstWithoutCredentialsOrLawNamePaths() {
        assertThat(LawSourceUrl.detail("270551")).isEqualTo("https://www.law.go.kr/lsInfoP.do?lsiSeq=270551");
        for (String invalid : new String[]{null, "", "123&OC=secret", "../123"}) {
            assertThatThrownBy(() -> LawSourceUrl.detail(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void preservesValidNamedLawAndCaseLinksButRejectsAmbiguousLegacyMst() {
        for (String valid : new String[]{"https://www.law.go.kr/법령/민법", "https://www.law.go.kr/precInfoP.do?precSeq=123"}) {
            assertThat(LawSourceUrl.publicLink(valid)).isEqualTo(valid);
        }
        assertThatThrownBy(() -> LawSourceUrl.publicLink("https://www.law.go.kr/법령?lsiSeq=123&lsiSeq=456"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
