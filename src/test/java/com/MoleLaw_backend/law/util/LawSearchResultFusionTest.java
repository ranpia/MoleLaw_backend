package com.MoleLaw_backend.law.util;

import com.MoleLaw_backend.law.dto.vector.LawSearchEvidence;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class LawSearchResultFusionTest {
    private LawSearchEvidence hit(String id) {
        return new LawSearchEvidence(id, "source", "123", "1", "20260101", "조문", "청크", "전체 본문",
                "https://www.law.go.kr", .8, "revision", "fingerprint", "generation");
    }
    @Test void allQueriesContributeRegardlessOfTheirOrderAndDuplicateArticlesAppearOnce() {
        var a = hit("123:1"); var b = hit("123:2"); var shared = hit("123:3");
        var first = List.of(a, shared); var second = List.of(b, shared);
        assertThat(LawSearchResultFusion.fuse(List.of(first, second)))
                .containsExactlyElementsOf(LawSearchResultFusion.fuse(List.of(second, first)))
                .containsExactly(shared, a, b);
        assertThat(a.score()).isEqualTo(.8);
    }
    @Test void explicitArticleIsNotDrownedOutByRepeatedSemanticMatches() {
        var exact = hit("123:575"); var generic = hit("123:580");
        assertThat(LawSearchResultFusion.fuse(List.of(List.of(exact), List.of(generic), List.of(generic)), Set.of(exact.unitId())))
                .containsExactly(exact, generic);
    }
}
