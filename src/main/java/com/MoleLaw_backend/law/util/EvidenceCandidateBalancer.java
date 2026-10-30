package com.MoleLaw_backend.law.util;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Balances retrieved candidates, never asserts relevance or completeness of an issue. */
public final class EvidenceCandidateBalancer {
    private EvidenceCandidateBalancer() { }

    public static List<EvidenceItem> select(List<EvidenceItem> ranked, List<List<String>> groups,
            Set<String> explicitUnits, Set<String> retained, int maxItems, int maxBytes) {
        Map<String, EvidenceItem> pool = new LinkedHashMap<>();
        Map<String, Integer> rank = new HashMap<>();
        Map<String, Integer> sizes = new HashMap<>();
        for (var item : ranked) {
            int size = item.content().getBytes(StandardCharsets.UTF_8).length;
            if (size > 128 * 1024) continue;
            rank.putIfAbsent(item.sourceId(), rank.size());
            pool.putIfAbsent(item.sourceId(), item);
            sizes.putIfAbsent(item.sourceId(), size);
        }
        List<EvidenceItem> selected = new ArrayList<>();
        Set<String> used = new HashSet<>();
        Map<String, Integer> lawCounts = new HashMap<>();
        int bytes = 0;
        // Exact article requests retain priority. They are still candidates, not verified citations.
        for (var item : pool.values()) {
            int size = sizes.get(item.sourceId());
            if (explicitUnits.contains(item.sourceId()) && selected.size() < maxItems && bytes + size <= maxBytes) {
                selected.add(item); used.add(item.sourceId());
                lawCounts.merge(law(item), 1, Integer::sum); bytes += size;
            }
        }
        var activeGroups = groups.stream().map(ids -> ids.stream().distinct().map(pool::get)
                .filter(java.util.Objects::nonNull).toList()).filter(items -> !items.isEmpty()).toList();
        boolean progress = true;
        while (selected.size() < maxItems && progress) {
            progress = false;
            for (var group : activeGroups) {
                if (selected.size() == maxItems) break;
                var next = next(group, used, lawCounts, retained, rank, sizes, maxBytes - bytes);
                if (next == null) continue;
                selected.add(next); used.add(next.sourceId()); bytes += sizes.get(next.sourceId());
                lawCounts.merge(law(next), 1, Integer::sum); progress = true;
            }
        }
        while (selected.size() < maxItems) {
            var next = next(List.copyOf(pool.values()), used, lawCounts, retained, rank, sizes, maxBytes - bytes);
            if (next == null) break;
            selected.add(next); used.add(next.sourceId()); bytes += sizes.get(next.sourceId());
            lawCounts.merge(law(next), 1, Integer::sum);
        }
        return List.copyOf(selected);
    }

    private static EvidenceItem next(List<EvidenceItem> candidates, Set<String> used, Map<String, Integer> counts,
            Set<String> retained, Map<String, Integer> ranks, Map<String, Integer> sizes, int remainingBytes) {
        return candidates.stream().filter(item -> !used.contains(item.sourceId())
                && sizes.get(item.sourceId()) <= remainingBytes)
                .min(Comparator.comparingInt((EvidenceItem item) -> counts.getOrDefault(law(item), 0))
                        .thenComparingInt(item -> retained.contains(item.id()) ? 0 : 1)
                        .thenComparingInt(item -> ranks.get(item.sourceId()))).orElse(null);
    }

    private static String law(EvidenceItem item) {
        return item.provenance() == null ? item.sourceId() : item.provenance().mst();
    }
}
