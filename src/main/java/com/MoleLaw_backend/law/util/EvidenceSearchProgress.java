package com.MoleLaw_backend.law.util;

import com.MoleLaw_backend.law.dto.vector.LawSearchEvidence;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps query-to-candidate associations across rounds; association alone never proves relevance. */
public final class EvidenceSearchProgress {
    private final List<String> initialQueries;
    private final Map<String, List<String>> results = new LinkedHashMap<>();
    private final Set<String> explicitUnits = new HashSet<>();

    public EvidenceSearchProgress(List<String> initialQueries) {
        this.initialQueries = initialQueries.stream().map(LawSearchQueryKey::of).distinct().toList();
    }

    public void record(String query, List<LawSearchEvidence> hits) {
        results.put(LawSearchQueryKey.of(query), hits.stream().map(LawSearchEvidence::unitId).distinct().toList());
        for (var hit : hits) {
            String lawTitle = hit.title().replaceFirst("\\s+제[0-9]+조.*$", "");
            if (LawArticleQuery.paths(query, lawTitle).contains(hit.unitId().substring(hit.unitId().indexOf(':') + 1)))
                explicitUnits.add(hit.unitId());
        }
    }

    public List<List<String>> groups() {
        var groups = new ArrayList<List<String>>();
        for (String query : initialQueries) groups.add(results.getOrDefault(query, List.of()));
        var additional = results.entrySet().stream().filter(entry -> !initialQueries.contains(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream()).distinct().toList();
        if (!additional.isEmpty()) groups.add(additional);
        return List.copyOf(groups);
    }

    public Set<String> explicitUnits() { return Set.copyOf(explicitUnits); }
}
