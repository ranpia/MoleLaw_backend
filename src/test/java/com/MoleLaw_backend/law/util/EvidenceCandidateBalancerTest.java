package com.MoleLaw_backend.law.util;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.law.dto.evidence.EvidenceProvenance;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EvidenceCandidateBalancerTest {
    @Test void sharedHighRankedLawCannotDisplaceTheOtherIssuesCandidates() {
        var dominant = IntStream.rangeClosed(1, 15).mapToObj(i -> item("A", i, "본문")).toList();
        var other = IntStream.rangeClosed(1, 15).mapToObj(i -> item("B", i, "본문")).toList();
        var ranked = new ArrayList<>(dominant); ranked.addAll(other);
        var first = dominant.stream().map(EvidenceItem::sourceId).toList();
        var second = new ArrayList<>(first.subList(0, 10));
        second.addAll(other.stream().map(EvidenceItem::sourceId).toList());
        var selected = EvidenceCandidateBalancer.select(ranked, List.of(first, second), Set.of(), Set.of(), 15, 1_048_576);
        assertThat(selected).hasSize(15);
        assertThat(selected.stream().filter(item -> item.provenance().mst().equals("B")).count()).isGreaterThanOrEqualTo(7);
        assertThat(selected).allSatisfy(item -> assertThat(ranked).contains(item));
    }

    @Test void fourIndependentGroupsEachKeepCandidatesWithinTheTopFifteen() {
        var pool = IntStream.rangeClosed(1, 60).mapToObj(i -> item("same-law", i, "本文")).toList();
        var groups = IntStream.range(0, 4).mapToObj(group -> pool.subList(group * 15, group * 15 + 15).stream()
                .map(EvidenceItem::sourceId).toList()).toList();
        var selected = EvidenceCandidateBalancer.select(pool, groups, Set.of(), Set.of(), 15, 1_048_576);
        for (var group : groups)
            assertThat(selected.stream().filter(item -> group.contains(item.sourceId())).count()).isBetween(3L, 4L);
    }

    @Test void exactArticlesAndPreviouslyReadEvidenceKeepPriorityWithoutDuplicateSources() {
        var first = item("A", 1, "본문");
        var second = item("A", 2, "본문");
        var exact = item("B", 1, "본문");
        var pool = List.of(first, second, exact, first);
        var selected = EvidenceCandidateBalancer.select(pool, List.of(List.of(first.sourceId(), second.sourceId()),
                List.of(exact.sourceId(), first.sourceId())), Set.of(exact.sourceId()), Set.of(second.id()), 3, 1024);
        assertThat(selected).containsExactly(exact, second, first);
    }

    @Test void wholeArticleByteLimitSkipsOversizedSourcesAndNeverTruncates() {
        var huge = item("A", 1, "x".repeat(131073));
        var first = item("A", 2, "12345");
        var second = item("B", 1, "67890");
        var third = item("B", 2, "abcdef");
        var selected = EvidenceCandidateBalancer.select(List.of(huge, first, second, third),
                List.of(List.of(huge.sourceId(), first.sourceId()), List.of(second.sourceId(), third.sourceId())),
                Set.of(), Set.of(), 15, 10);
        assertThat(selected).containsExactly(first, second);
        assertThat(selected).extracting(EvidenceItem::content).containsExactly("12345", "67890");
    }

    private EvidenceItem item(String law, int article, String content) {
        return new EvidenceItem(law + "-" + article, "LAW_QDRANT", law + ":" + article, "제" + article + "조",
                "https://www.law.go.kr", content,
                new EvidenceProvenance("source-" + law, law, "law-" + law, "20260101", "" + article, "revision", "fingerprint", "generation"));
    }
}
