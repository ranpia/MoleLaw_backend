package com.MoleLaw_backend.law.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LawArticleQueryTest {
    @Test void readsExplicitArticlesAndBranchesWithinExactLawPrefix() {
        assertThat(LawArticleQuery.paths("민법 제575조 제580조 제582조 권리행사", "민법"))
                .containsExactly("575", "580", "582");
        assertThat(LawArticleQuery.paths("근로기준법 제43조의2", "근로기준법")).containsExactly("43-2");
    }
    @Test void doesNotConfusePartialNamesOrSubordinateLegislation() {
        assertThat(LawArticleQuery.paths("난민법 제2조", "민법")).isEmpty();
        assertThat(LawArticleQuery.paths("민법 시행령 제2조", "민법")).isEmpty();
        assertThat(LawArticleQuery.paths("근로기준법 시행령 제7조", "근로기준법 시행령")).containsExactly("7");
        assertThat(LawArticleQuery.paths("권리행사 민법 제2조", "민법")).isEmpty();
    }
}
