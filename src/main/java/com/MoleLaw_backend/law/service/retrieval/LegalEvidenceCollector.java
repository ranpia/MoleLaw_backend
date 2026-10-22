package com.MoleLaw_backend.law.service.retrieval;

import com.MoleLaw_backend.law.dto.vector.LawSearchEvidence;
import com.MoleLaw_backend.law.dto.tool.LegalToolContracts;
import com.MoleLaw_backend.law.exception.LegalToolException;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.law.dto.evidence.EvidenceProvenance;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.law.dto.request.LawSearchFilters;
import com.MoleLaw_backend.law.dto.retrieval.EvidenceDecision;
import com.MoleLaw_backend.law.service.retrieval.EvidenceDecisionPlanner;
import com.MoleLaw_backend.law.service.tool.LawRagSearchToolService;
import com.MoleLaw_backend.law.service.tool.LawVectorizationToolService;
import com.MoleLaw_backend.law.service.tool.LegalApiToolService;
import com.MoleLaw_backend.law.service.tool.LegalToolContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.HexFormat;

/** Bounded first-question retrieval. Follow-up answers never invoke this collector. */
@Service
public class LegalEvidenceCollector {
    private final ObjectProvider<LawRagSearchToolService> searches;
    private final ObjectProvider<LawVectorizationToolService> vectors;
    private final LegalApiToolService laws;
    private final EvidenceDecisionPlanner planner;

    public LegalEvidenceCollector(ObjectProvider<LawRagSearchToolService> searches,
            ObjectProvider<LawVectorizationToolService> vectors, LegalApiToolService laws,
            EvidenceDecisionPlanner planner) {
        this.searches = searches;
        this.vectors = vectors;
        this.laws = laws;
        this.planner = planner;
    }

    public List<EvidenceItem> collect(String question) {
        if (question == null || question.isBlank() || question.codePointCount(0, question.length()) > 2000)
            throw new MolelawException(ErrorCode.INVALID_REQUEST);
        var search = searches.getIfAvailable();
        var vector = vectors.getIfAvailable();
        if (search == null || vector == null) throw new MolelawException(ErrorCode.RETRIEVAL_UNAVAILABLE);
        Clock clock = Clock.systemUTC();
        var deadline = clock.instant().plus(Duration.ofMinutes(3));
        var context = new LegalToolContext(LegalToolContext.Purpose.FIRST_QUESTION,
                UUID.randomUUID().toString(), deadline, 128, clock);
        Map<String, String> ids = new HashMap<>();
        Map<EvidenceDecision.LawSearch, PageCursor> pages = new HashMap<>();
        Set<String> details = new HashSet<>(), queries = new HashSet<>();
        try {
            List<EvidenceItem> evidence = merge(search.searchTop15(context, question), List.of(), ids);
            queries.add(question.strip());
            for (int round = 0; round <= 4; round++) {
                if (!clock.instant().isBefore(deadline)) throw insufficient();
                var decision = planner.decide(question, evidence);
                if (!clock.instant().isBefore(deadline)) throw insufficient();
                decision.validateAgainst(evidence.stream().map(EvidenceItem::id).toList());
                if (round == 4 && decision.action() != EvidenceDecision.Action.ANSWER_READY) throw insufficient();
                switch (decision.action()) {
                    case ANSWER_READY -> {
                        Set<String> selected = Set.copyOf(decision.evidenceIds());
                        return evidence.stream().filter(item -> selected.contains(item.id())).toList();
                    }
                    case INSUFFICIENT -> throw insufficient();
                    case SEARCH_AGAIN -> {
                        List<EvidenceItem> before = evidence;
                        for (String query : decision.searchQueries()) {
                            if (!queries.add(query)) continue;
                            evidence = merge(search.searchTop15(context, query), evidence, ids);
                        }
                        if (!changed(before, evidence)) throw insufficient();
                    }
                    case COLLECT_LAW -> {
                        boolean progressed = false;
                        for (var condition : decision.lawSearches()) {
                            PageCursor cursor = pages.computeIfAbsent(condition, ignored -> new PageCursor());
                            int collected = 0;
                            while (details.size() < 6 && collected < 3) {
                                if (cursor.offset >= cursor.candidates.size()) {
                                    if (cursor.nextPage > 3) break;
                                    var filter = new LawSearchFilters(null, null, 2, condition.exactName());
                                    var result = laws.searchLaws(context, new LegalToolContracts.SearchRequest(
                                            condition.query(), condition.field() == EvidenceDecision.SearchField.TITLE ? 1 : 2,
                                            condition.ministry(), cursor.nextPage, 20, filter));
                                    cursor.nextPage = result.hasNext() ? cursor.nextPage + 1 : 4;
                                    cursor.candidates = result.candidates();
                                    cursor.offset = 0;
                                    if (cursor.candidates.isEmpty()) continue;
                                }
                                var candidate = cursor.candidates.get(cursor.offset++);
                                if (!details.add(candidate.id())) continue;
                                var detail = laws.getLawDetail(context, candidate.id());
                                if (detail.contentAvailable()) {
                                    vector.vectorizeLaw(context, detail.referenceId());
                                    collected++;
                                    progressed = true;
                                }
                            }
                        }
                        if (!progressed) throw insufficient();
                        var refreshed = merge(search.searchTop15(context, question), evidence, ids);
                        if (!changed(evidence, refreshed)) throw insufficient();
                        evidence = refreshed;
                    }
                }
            }
            throw insufficient();
        } catch (LegalToolException failure) {
            if (failure.getReason() == LegalToolException.Reason.BUSY)
                throw new MolelawException(ErrorCode.RETRIEVAL_BUSY);
            if (failure.getReason() == LegalToolException.Reason.BUDGET_EXHAUSTED) throw insufficient();
            throw new MolelawException(ErrorCode.RETRIEVAL_UNAVAILABLE);
        } catch (IllegalArgumentException failure) {
            throw new MolelawException(ErrorCode.RETRIEVAL_UNAVAILABLE);
        }
    }

    private List<EvidenceItem> merge(List<LawSearchEvidence> hits,
            List<EvidenceItem> previous, Map<String, String> ids) {
        Map<String, EvidenceItem> merged = new LinkedHashMap<>();
        Map<String, String> observedVersions = new HashMap<>();
        int bytes = 0;
        for (var hit : hits) {
            String version = hit.referenceId() + "|" + hit.modelRevision() + "|" + hit.configFingerprint() + "|" + hit.generation();
            String previousVersion = observedVersions.putIfAbsent(hit.externalId(), version);
            if (previousVersion != null && !previousVersion.equals(version)) {
                throw new IllegalArgumentException("Mixed indexed source generations");
            }
            String content = hit.articleText();
            int size = content.getBytes(StandardCharsets.UTF_8).length;
            if (size > 128 * 1024 || bytes + size > 1024 * 1024 || merged.size() >= 15) continue;
            String prefix = hit.externalId() + ":";
            if (!hit.unitId().startsWith(prefix)) throw new IllegalArgumentException("Invalid indexed source identity");
            var provenance = new EvidenceProvenance(hit.referenceId(), hit.externalId(), hit.lawId(),
                    blankToNull(hit.effectiveDate()), hit.unitId().substring(prefix.length()), hit.modelRevision(),
                    hit.configFingerprint(), hit.generation());
            String versionKey = hit.unitId() + "|" + hit.referenceId() + "|" + hit.modelRevision()
                    + "|" + hit.configFingerprint() + "|" + hit.generation() + "|" + digest(content);
            var item = new EvidenceItem(ids.computeIfAbsent(versionKey, ignored -> UUID.randomUUID().toString()),
                    "LAW_QDRANT", hit.unitId(), hit.title(), hit.sourceUrl(), content, provenance);
            if (merged.putIfAbsent(item.sourceId(), item) == null) bytes += size;
        }
        for (var item : previous) {
            var provenance = item.provenance();
            if (provenance != null && observedVersions.containsKey(provenance.mst())) {
                String version = provenance.sourceUuid() + "|" + provenance.modelRevision() + "|"
                        + provenance.configFingerprint() + "|" + provenance.generation();
                if (!observedVersions.get(provenance.mst()).equals(version)) continue;
            }
            int size = item.content().getBytes(StandardCharsets.UTF_8).length;
            if (merged.size() >= 15) break;
            if (bytes + size <= 1024 * 1024 && merged.putIfAbsent(item.sourceId(), item) == null) bytes += size;
        }
        return List.copyOf(merged.values());
    }

    private static boolean changed(List<EvidenceItem> before, List<EvidenceItem> after) {
        return !new HashSet<>(before).equals(new HashSet<>(after));
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static final class PageCursor {
        private int nextPage = 1;
        private int offset;
        private List<LegalToolContracts.Candidate> candidates = List.of();
    }

    private static MolelawException insufficient() { return new MolelawException(ErrorCode.EVIDENCE_INSUFFICIENT); }
}
