package com.MoleLaw_backend.law.service.retrieval;

import com.MoleLaw_backend.law.dto.vector.LawSearchEvidence;
import com.MoleLaw_backend.law.config.LawCandidateSelectionProperties;
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
import org.springframework.beans.factory.annotation.Value;
import com.MoleLaw_backend.law.dto.retrieval.RetrievalBackend;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
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
import java.util.ArrayList;
import com.MoleLaw_backend.law.dto.retrieval.QuestionRetrievalPlan;
import com.MoleLaw_backend.law.dto.retrieval.CollectedLegalEvidence;
import com.MoleLaw_backend.law.dto.retrieval.RetrievalAttempt;

/** Bounded first-question retrieval. Follow-up answers never invoke this collector. */
@Service
@Slf4j
public class LegalEvidenceCollector {
    private final ObjectProvider<LawRagSearchToolService> searches;
    private final ObjectProvider<LawVectorizationToolService> vectors;
    private final LegalApiToolService laws;
    private final EvidenceDecisionPlanner planner;
    private final QuestionRetrievalPlanner questions;
    private final LawCandidateSelector candidates;
    private final AiSearchEvidenceCollector aiSearch;
    private final RetrievalBackend backend;
    private final LawCandidateSelectionProperties selection;

    public LegalEvidenceCollector(ObjectProvider<LawRagSearchToolService> searches,
            ObjectProvider<LawVectorizationToolService> vectors, LegalApiToolService laws,
            EvidenceDecisionPlanner planner, QuestionRetrievalPlanner questions, LawCandidateSelector candidates,
            AiSearchEvidenceCollector aiSearch,
            @Value("${app.law-retrieval.backend:QDRANT}") RetrievalBackend backend,
            LawCandidateSelectionProperties selection) {
        this.searches = searches;
        this.vectors = vectors;
        this.laws = laws;
        this.planner = planner;
        this.questions = questions;
        this.candidates = candidates;
        this.aiSearch = aiSearch;
        this.backend = backend;
        this.selection = selection;
    }

    public List<EvidenceItem> collect(String question) {
        return collectForAnswer(question).evidence();
    }

    public CollectedLegalEvidence collectForAnswer(String question) {
        return collectForAnswer(question, com.MoleLaw_backend.law.dto.retrieval.RetrievalObserver.NONE);
    }

    public CollectedLegalEvidence collectForAnswer(String question, com.MoleLaw_backend.law.dto.retrieval.RetrievalObserver observer) {
        if (question == null || question.isBlank() || question.codePointCount(0, question.length()) > 2000)
            throw new MolelawException(ErrorCode.INVALID_REQUEST);
        if (backend == RetrievalBackend.AI_SEARCH) return aiSearch.collect(question, observer);
        var search = searches.getIfAvailable();
        var vector = vectors.getIfAvailable();
        if (search == null || vector == null) throw new MolelawException(ErrorCode.RETRIEVAL_UNAVAILABLE);
        Clock clock = Clock.systemUTC();
        var deadline = clock.instant().plus(Duration.ofMinutes(3));
        var context = new LegalToolContext(LegalToolContext.Purpose.FIRST_QUESTION,
                observer.traceId() == null ? UUID.randomUUID().toString() : observer.traceId(), deadline, 128, clock);
        Map<String, String> ids = new HashMap<>();
        Map<String, PageCursor> pages = new HashMap<>();
        Set<String> details = new HashSet<>(), queries = new HashSet<>();
        Set<String> observedLaws = new HashSet<>();
        Set<String> retained = new java.util.LinkedHashSet<>();
        List<RetrievalAttempt> attempts = new ArrayList<>();
        String stage = "QUESTION_PLANNING";
        try {
            observer.progress(stage);
            log.info("Legal collection executionId={} stage={}", context.executionId(), stage);
            var plan = questions.plan(question);
            context.checkDeadline();
            log.info("Legal collection executionId={} questionType={} responseScope={}",
                    context.executionId(), plan.type(), plan.responseScope());
            if (plan.type() == QuestionRetrievalPlan.Type.OUT_OF_SCOPE) throw insufficient();
            stage = "INITIAL_SEARCH";
            observer.progress(stage);
            log.info("Legal collection executionId={} stage={} queryCount={}",
                    context.executionId(), stage, plan.searchQueries().size());
            List<EvidenceItem> evidence = searchBatch(plan.searchQueries(), List.of(), ids, context, search, attempts, retained);
            plan.searchQueries().stream().map(com.MoleLaw_backend.law.util.LawSearchQueryKey::of).forEach(queries::add);
            for (int round = 0; round <= 4; round++) {
                evidence.stream().map(EvidenceItem::provenance).filter(java.util.Objects::nonNull)
                        .map(EvidenceProvenance::mst).forEach(observedLaws::add);
                context.checkDeadline();
                stage = "DECIDING";
                observer.progress(stage);
                log.info("Legal collection executionId={} stage={} round={} evidenceCount={}",
                        context.executionId(), stage, round, evidence.size());
                var decision = planner.decide(question, evidence, plan, List.copyOf(attempts), 4 - round);
                if (round == 0 && decision.action() == EvidenceDecision.Action.INSUFFICIENT
                        && plan.type() == QuestionRetrievalPlan.Type.LAW_DISCOVERY
                        && plan.responseScope() == QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE) {
                    context.checkDeadline();
                    log.info("Legal collection executionId={} stage=RECONSIDER_DISCOVERY", context.executionId());
                    decision = planner.reconsiderDiscovery(question, evidence, plan, List.copyOf(attempts), 4 - round);
                }
                log.info("Legal collection executionId={} round={} action={} evidenceCount={}",
                        context.executionId(), round, decision.action(), evidence.size());
                context.checkDeadline();
                decision.validateAgainst(evidence.stream().map(EvidenceItem::id).toList());
                retained.addAll(decision.evidenceIds());
                observer.evidence(evidence.stream().filter(item -> retained.contains(item.id())).toList());
                if (round == 4 && decision.action() != EvidenceDecision.Action.ANSWER_READY
                        && decision.action() != EvidenceDecision.Action.ANSWER_PARTIAL) throw insufficient();
                switch (decision.action()) {
                    case ANSWER_READY -> {
                        Set<String> selected = Set.copyOf(decision.evidenceIds());
                        return new CollectedLegalEvidence(plan,
                                evidence.stream().filter(item -> selected.contains(item.id())).toList());
                    }
                    case INSUFFICIENT -> throw insufficient();
                    case ANSWER_PARTIAL -> {
                        Set<String> selected = Set.copyOf(decision.evidenceIds());
                        var guidance = new QuestionRetrievalPlan(plan.type(), QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE,
                                plan.searchQueries(), plan.lawSearches(), plan.missingFacts());
                        return new CollectedLegalEvidence(guidance,
                                evidence.stream().filter(item -> selected.contains(item.id())).toList(), decision.missingIssues());
                    }
                    case SEARCH_AGAIN -> {
                        stage = "SEARCH_AGAIN";
                        observer.progress(stage);
                        List<String> freshQueries = new ArrayList<>();
                        for (String query : decision.searchQueries()) {
                            if (!queries.add(com.MoleLaw_backend.law.util.LawSearchQueryKey.of(query))) {
                                attempts.add(new RetrievalAttempt(RetrievalAttempt.Kind.VECTOR_SEARCH, query,
                                        null, null, RetrievalAttempt.Outcome.DUPLICATE, 0, false));
                                log.info("Legal collection executionId={} stage=SEARCH_SKIPPED reason=DUPLICATE_QUERY", context.executionId());
                                continue;
                            }
                            freshQueries.add(query);
                        }
                        if (!freshQueries.isEmpty())
                            evidence = searchBatch(freshQueries, evidence, ids, context, search, attempts, retained);
                    }
                    case COLLECT_LAW -> {
                        boolean progressed = false;
                        boolean indexed = false;
                        for (var condition : decision.lawSearches()) {
                            if (indexed) break;
                            String conditionKey = com.MoleLaw_backend.law.util.LawSearchQueryKey.of(condition.query())
                                    + "|" + condition.field() + "|" + condition.exactName() + "|" + condition.ministry();
                            PageCursor cursor = pages.computeIfAbsent(conditionKey, ignored -> new PageCursor());
                            while (details.size() < 6 && !indexed) {
                                if (cursor.offset >= cursor.candidates.size()) {
                                    if (cursor.complete) break;
                                    Map<String, LegalToolContracts.Candidate> pool = new LinkedHashMap<>();
                                    Map<String, Integer> scores = new HashMap<>();
                                    while (!cursor.complete) {
                                        var filter = new LawSearchFilters(null, null, LawSearchFilters.CURRENT, condition.exactName());
                                        stage = "LAW_SEARCH";
                                        observer.progress(stage);
                                        log.info("Legal collection executionId={} stage={} page={}",
                                                context.executionId(), stage, cursor.nextPage);
                                        var result = laws.searchLaws(context, new LegalToolContracts.SearchRequest(
                                                condition.query(), condition.field() == EvidenceDecision.SearchField.TITLE ? 1 : 2,
                                                condition.ministry(), cursor.nextPage, 20, filter));
                                        if (result.status() == LegalToolContracts.Status.BUDGET_EXHAUSTED)
                                            throw new LegalToolException(LegalToolException.Reason.BUDGET_EXHAUSTED, false);
                                        attempts.add(lawAttempt(condition, cursor.nextPage, result));
                                        progressed = true; // A new observed empty page is feedback for replanning.
                                        cursor.complete = !result.hasNext();
                                        if (!cursor.complete && cursor.nextPage >= 10000)
                                            throw new LegalToolException(LegalToolException.Reason.BUDGET_EXHAUSTED, false);
                                        cursor.nextPage++;
                                        stage = "CANDIDATE_REVIEW";
                                        observer.progress(stage);
                                        var unseen = result.candidates().stream()
                                                .filter(c -> !details.contains(c.id()) && !observedLaws.contains(c.id())
                                                        && !pool.containsKey(c.id())).toList();
                                        var rankings = candidates.rank(question, plan, condition, unseen);
                                        unseen.forEach(c -> pool.put(c.id(), c));
                                        rankings.forEach(item -> scores.put(item.candidateId(), item.score()));
                                        context.checkDeadline();
                                        log.info("Legal collection executionId={} stage={} page={} rankedCount={} hasNext={}",
                                                context.executionId(), stage, result.page(), rankings.size(), result.hasNext());
                                        if (!unseen.isEmpty() && rankings.stream().noneMatch(item -> item.score() >= selection.getMinimumScore()))
                                            attempts.add(new RetrievalAttempt(RetrievalAttempt.Kind.LAW_SEARCH,
                                                    condition.query(), condition, result.page(),
                                                    RetrievalAttempt.Outcome.NO_RELEVANT_CANDIDATES, 0, result.hasNext()));
                                        }
                                    cursor.candidates = pool.values().stream()
                                            .filter(c -> scores.getOrDefault(c.id(), 0) >= selection.getMinimumScore())
                                            .sorted(java.util.Comparator.comparingInt(
                                                    (LegalToolContracts.Candidate c) -> scores.get(c.id())).reversed())
                                            .limit(selection.getMaxCandidates()).toList();
                                    log.info("Legal collection executionId={} stage=RERANK_COMPLETE pages={} candidateCount={} selectedCount={}",
                                            context.executionId(), cursor.nextPage - 1, pool.size(), cursor.candidates.size());
                                    cursor.offset = 0;
                                    if (cursor.candidates.isEmpty()) continue;
                                }
                                var candidate = cursor.candidates.get(cursor.offset++);
                                if (observedLaws.contains(candidate.id()) || !details.add(candidate.id())) continue;
                                progressed = true;
                                var published = vector.publishedSource(candidate);
                                if (published.isPresent()) {
                                    stage = "LAW_INDEX_REUSE";
                                    observer.progress(stage);
                                    log.info("Legal collection executionId={} stage={}", context.executionId(), stage);
                                    vector.reindexStoredLaw(context, published.get());
                                    indexed = true;
                                    continue;
                                }
                                stage = "LAW_DETAIL";
                                observer.progress(stage);
                                log.info("Legal collection executionId={} stage={}", context.executionId(), stage);
                                var detail = laws.getLawDetail(context, candidate.id());
                                if (detail.contentAvailable()) {
                                    stage = "LAW_INDEX";
                                    observer.progress(stage);
                                    log.info("Legal collection executionId={} stage={}", context.executionId(), stage);
                                    vector.vectorizeLaw(context, detail.referenceId());
                                    indexed = true;
                                }
                            }
                        }
                        if (!progressed) throw insufficient();
                        if (indexed) {
                            stage = "SEARCH_AFTER_INDEX";
                            observer.progress(stage);
                            log.info("Legal collection executionId={} stage={}", context.executionId(), stage);
                            evidence = searchBatch(plan.searchQueries(), evidence, ids, context, search, attempts, retained);
                        }
                    }
                }
            }
            throw insufficient();
        } catch (LegalToolException failure) {
            log.warn("Legal collection call budgets executionId={} budgets={}", context.executionId(), context.callBudgets());
            log.warn("Legal collection failed executionId={} stage={} reason={} retryable={} calls={}",
                    context.executionId(), stage, failure.getReason(), failure.isRetryable(), context.callsUsed());
            if (failure.getReason() == LegalToolException.Reason.BUSY)
                throw new MolelawException(ErrorCode.RETRIEVAL_BUSY);
            if (failure.getReason() == LegalToolException.Reason.BUDGET_EXHAUSTED) throw insufficient();
            throw new MolelawException(ErrorCode.RETRIEVAL_UNAVAILABLE);
        } catch (IllegalArgumentException failure) {
            log.warn("Legal collection failed executionId={} stage={} errorType={}",
                    context.executionId(), stage, failure.getClass().getSimpleName());
            throw new MolelawException(ErrorCode.RETRIEVAL_UNAVAILABLE);
        }
    }

    private List<EvidenceItem> searchBatch(List<String> queries, List<EvidenceItem> before, Map<String, String> ids,
            LegalToolContext context, LawRagSearchToolService search, List<RetrievalAttempt> attempts, Set<String> retained) {
        List<List<LawSearchEvidence>> rankings = new ArrayList<>();
        Set<String> explicitUnits = new HashSet<>();
        for (String query : queries) {
            var hits = search.searchTop15(context, query);
            rankings.add(hits);
            for (var hit : hits) {
                String lawTitle = hit.title().replaceFirst("\\s+제[0-9]+조.*$", "");
                if (com.MoleLaw_backend.law.util.LawArticleQuery.paths(query, lawTitle)
                        .contains(hit.unitId().substring(hit.unitId().indexOf(':') + 1))) explicitUnits.add(hit.unitId());
            }
            var after = merge(hits, before, ids, Set.of());
            var outcome = hits.isEmpty() ? RetrievalAttempt.Outcome.NO_RESULTS : changed(before, after)
                    ? RetrievalAttempt.Outcome.AVAILABLE : RetrievalAttempt.Outcome.NO_NEW_EVIDENCE;
            attempts.add(new RetrievalAttempt(RetrievalAttempt.Kind.VECTOR_SEARCH, query, null, null,
                    outcome, hits.size(), false));
        }
        return merge(com.MoleLaw_backend.law.util.LawSearchResultFusion.fuse(rankings, explicitUnits), before, ids, retained);
    }

    private RetrievalAttempt lawAttempt(EvidenceDecision.LawSearch condition, int page,
            LegalToolContracts.SearchResult result) {
        var outcome = switch (result.status()) {
            case AVAILABLE -> RetrievalAttempt.Outcome.AVAILABLE;
            case DUPLICATE -> RetrievalAttempt.Outcome.DUPLICATE;
            case EXHAUSTED -> RetrievalAttempt.Outcome.EXHAUSTED;
            case EMPTY -> condition.exactName() && result.totalCount() > 0
                    ? RetrievalAttempt.Outcome.NO_EXACT_MATCH : RetrievalAttempt.Outcome.NO_RESULTS;
            case BUDGET_EXHAUSTED -> throw new IllegalStateException("Budget exhaustion must not become empty search feedback");
        };
        return new RetrievalAttempt(RetrievalAttempt.Kind.LAW_SEARCH, condition.query(), condition, page,
                outcome, result.candidates().size(), result.hasNext());
    }

    private List<EvidenceItem> merge(List<LawSearchEvidence> hits,
            List<EvidenceItem> previous, Map<String, String> ids, Set<String> retained) {
        Map<String, EvidenceItem> merged = new LinkedHashMap<>();
        Map<String, String> observedVersions = new HashMap<>();
        for (var hit : hits) {
            String version = hit.referenceId() + "|" + hit.modelRevision() + "|" + hit.configFingerprint() + "|" + hit.generation();
            String previousVersion = observedVersions.putIfAbsent(hit.externalId(), version);
            if (previousVersion != null && !previousVersion.equals(version)) {
                throw new IllegalArgumentException("Mixed indexed source generations");
            }
            String content = hit.articleText();
            int size = content.getBytes(StandardCharsets.UTF_8).length;
            if (size > 128 * 1024) continue;
            String prefix = hit.externalId() + ":";
            if (!hit.unitId().startsWith(prefix)) throw new IllegalArgumentException("Invalid indexed source identity");
            var provenance = new EvidenceProvenance(hit.referenceId(), hit.externalId(), hit.lawId(),
                    blankToNull(hit.effectiveDate()), hit.unitId().substring(prefix.length()), hit.modelRevision(),
                    hit.configFingerprint(), hit.generation());
            String versionKey = hit.unitId() + "|" + hit.referenceId() + "|" + hit.modelRevision()
                    + "|" + hit.configFingerprint() + "|" + hit.generation() + "|" + digest(content);
            var item = new EvidenceItem(ids.computeIfAbsent(versionKey, ignored -> UUID.randomUUID().toString()),
                    "LAW_QDRANT", hit.unitId(), hit.title(), hit.sourceUrl(), content, provenance);
            merged.putIfAbsent(item.sourceId(), item);
        }
        for (var item : previous) {
            var provenance = item.provenance();
            if (provenance != null && observedVersions.containsKey(provenance.mst())) {
                String version = provenance.sourceUuid() + "|" + provenance.modelRevision() + "|"
                        + provenance.configFingerprint() + "|" + provenance.generation();
                if (!observedVersions.get(provenance.mst()).equals(version)) continue;
            }
            merged.putIfAbsent(item.sourceId(), item);
        }
        // Preserve previously approved coverage before promoting fresh hits from the same laws.
        var retainedOrder = new LinkedHashMap<String, EvidenceItem>();
        for (var item : previous) {
            var current = merged.get(item.sourceId());
            if (retained.contains(item.id()) && current != null && current.id().equals(item.id()))
                retainedOrder.put(item.id(), current);
        }
        merged.values().stream().filter(item -> retained.contains(item.id()))
                .forEach(item -> retainedOrder.putIfAbsent(item.id(), item));
        var preferred = retainedOrder.values().stream().limit(8).toList();
        var ranked = new ArrayList<>(preferred);
        merged.values().stream().filter(item -> !preferred.contains(item)).forEach(ranked::add);
        List<EvidenceItem> bounded = new ArrayList<>();
        int bytes = 0;
        for (var item : ranked) {
            int size = item.content().getBytes(StandardCharsets.UTF_8).length;
            if (bounded.size() >= 15) break;
            if (size > 128 * 1024 || bytes + size > 1024 * 1024) continue;
            bounded.add(item);
            bytes += size;
        }
        return List.copyOf(bounded);
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
        private boolean complete;
        private int offset;
        private List<LegalToolContracts.Candidate> candidates = List.of();
    }

    private static MolelawException insufficient() { return new MolelawException(ErrorCode.EVIDENCE_INSUFFICIENT); }
}
