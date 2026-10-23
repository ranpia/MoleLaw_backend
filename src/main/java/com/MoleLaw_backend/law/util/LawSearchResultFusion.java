package com.MoleLaw_backend.law.util;

import com.MoleLaw_backend.law.dto.vector.LawSearchEvidence;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reciprocal-rank fusion for one query batch; raw similarity scores remain unchanged. */
public final class LawSearchResultFusion {
    private LawSearchResultFusion() { }

    public static List<LawSearchEvidence> fuse(List<List<LawSearchEvidence>> rankings) {
        return fuse(rankings, java.util.Set.of());
    }

    public static List<LawSearchEvidence> fuse(List<List<LawSearchEvidence>> rankings, java.util.Set<String> explicitUnits) {
        Map<String, LawSearchEvidence> hits = new LinkedHashMap<>();
        Map<String, Double> scores = new HashMap<>();
        Map<String, Integer> bestRanks = new HashMap<>();
        for (var ranking : rankings) {
            var seen = new java.util.HashSet<String>();
            for (int i = 0; i < ranking.size(); i++) {
                var hit = ranking.get(i);
                if (!seen.add(hit.unitId())) continue;
                var old = hits.putIfAbsent(hit.unitId(), hit);
                if (old != null && (!java.util.Objects.equals(old.generation(), hit.generation())
                        || !java.util.Objects.equals(old.referenceId(), hit.referenceId())))
                    throw new IllegalArgumentException("Mixed source versions in query batch");
                scores.merge(hit.unitId(), 1.0 / (61 + i), Double::sum);
                bestRanks.merge(hit.unitId(), i, Math::min);
            }
        }
        var result = new ArrayList<>(hits.values());
        result.sort(Comparator.comparingInt((LawSearchEvidence hit) -> explicitUnits.contains(hit.unitId()) ? 0 : 1)
                .thenComparing(Comparator.comparingDouble((LawSearchEvidence hit) -> scores.get(hit.unitId())).reversed())
                .thenComparingInt(hit -> bestRanks.get(hit.unitId())).thenComparing(LawSearchEvidence::unitId));
        return List.copyOf(result);
    }
}
