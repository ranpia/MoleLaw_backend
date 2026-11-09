package com.MoleLaw_backend.service.tool;

import com.MoleLaw_backend.dto.vector.LawSearchEvidence;
import com.MoleLaw_backend.config.LawCandidateSelectionProperties;
import com.MoleLaw_backend.dto.tool.LegalToolContracts;
import com.MoleLaw_backend.util.exception.LegalToolException;

import com.MoleLaw_backend.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.dto.evidence.EvidenceProvenance;
import com.MoleLaw_backend.util.exception.ErrorCode;
import com.MoleLaw_backend.util.exception.MolelawException;
import com.MoleLaw_backend.dto.request.LawSearchFilters;
import com.MoleLaw_backend.dto.retrieval.EvidenceDecision;
import com.MoleLaw_backend.util.EvidenceCandidateBalancer;
import com.MoleLaw_backend.util.EvidenceSearchProgress;
import com.MoleLaw_backend.util.LawCollectionQueue;
import com.MoleLaw_backend.util.LawSearchQueryKey;
import com.MoleLaw_backend.util.LawSearchResultFusion;
import com.MoleLaw_backend.service.tool.LawRagSearchToolService;
import com.MoleLaw_backend.service.tool.LawVectorizationToolService;
import com.MoleLaw_backend.service.tool.LegalApiToolService;
import com.MoleLaw_backend.service.tool.LegalToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import com.MoleLaw_backend.service.retrieval.LawCandidateSelector;
import com.MoleLaw_backend.service.tool.EvidenceReadToolService;
import com.MoleLaw_backend.dto.retrieval.RetrievalObserver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import com.knuddels.jtokkit.api.EncodingType;
import lombok.extern.slf4j.Slf4j;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.HexFormat;
import java.util.ArrayList;
import com.MoleLaw_backend.dto.retrieval.QuestionRetrievalPlan;
import com.MoleLaw_backend.dto.retrieval.CollectedLegalEvidence;
import com.MoleLaw_backend.dto.retrieval.RetrievalAttempt;

/** One authorized first-question execution. No model-controlled context, endpoints or budgets. */
@Slf4j
public final class LawRetrievalToolSession {
    private final String question;
    private final QuestionRetrievalPlan plan;
    private final LegalToolContext context;
    private final LawRagSearchToolService search;
    private final LawVectorizationToolService vector;
    private final LegalApiToolService laws;
    private final LawCandidateSelector candidates;
    private final EvidenceReadToolService reader;
    private final LawCandidateSelectionProperties selection;
    private final RetrievalObserver observer;
    private final boolean fixedIndexOnly;
    private final int bodyLimit;
    private final ObjectMapper mapper;
    private final JTokkitTokenCountEstimator tokens = new JTokkitTokenCountEstimator(EncodingType.CL100K_BASE);
    private final Map<String, String> ids = new HashMap<>();
    private final Set<String> queries = new HashSet<>();
    private final Set<String> lawQueries = new HashSet<>();
    private final Set<String> collected = new HashSet<>();
    private final Set<String> retained = new java.util.LinkedHashSet<>();
    private final Map<String, LegalToolContracts.Candidate> approved = new LinkedHashMap<>();
    private final Map<String, EvidenceItem> read = new LinkedHashMap<>();
    private final List<RetrievalAttempt> attempts = new ArrayList<>();
    private final EvidenceSearchProgress searchProgress;
    private List<EvidenceItem> evidence = List.of();
    private int toolCalls;
    private int bodyTokens;

    public LawRetrievalToolSession(String question, QuestionRetrievalPlan plan, LegalToolContext context,
            LawRagSearchToolService search, LawVectorizationToolService vector, LegalApiToolService laws,
            LawCandidateSelector candidates, EvidenceReadToolService reader,
            LawCandidateSelectionProperties selection, RetrievalObserver observer, boolean fixedIndexOnly,
            int bodyLimit, ObjectMapper mapper) {
        this.question = question; this.plan = plan; this.context = context; this.search = search;
        this.vector = vector; this.laws = laws; this.candidates = candidates; this.reader = reader;
        this.selection = selection; this.observer = observer; this.fixedIndexOnly = fixedIndexOnly;
        this.bodyLimit = bodyLimit; this.mapper = mapper;
        this.searchProgress = new EvidenceSearchProgress(plan.initialSearchQueries());
    }

    public void checkActive() { context.checkDeadline(); }
    public String executionId() { return context.executionId(); }
    public boolean fixedIndexOnly() { return fixedIndexOnly; }
    public List<String> readIds() { return evidence.stream().map(EvidenceItem::id).filter(read::containsKey).toList(); }

    private void begin(String stage) {
        checkActive();
        observer.progress(stage); // Also checks stream cancellation before any side effect.
        if (++toolCalls > 32) throw insufficient();
        log.info("Law tool executionId={} stage={} call={} readCount={}", context.executionId(), stage, toolCalls, read.size());
    }

    @Tool(name = "searchLawArticles", description = "Search stored law articles using relevant queries chosen by the model, without a fixed keyword count or vocabulary. Returns discovery metadata and authorized evidence UUIDs, not read bodies. Empty or unchanged results require a changed query or searchLaws. Read selected IDs with readLawArticles before citing.")
    public synchronized Map<String, Object> searchArticles(
            @ToolParam(description = "Relevant distinct queries, no fixed count; each within the article search API's 2000-character limit") List<String> searchQueries) {
        begin("INITIAL_SEARCH");
        if (searchQueries == null || searchQueries.isEmpty())
            throw new IllegalArgumentException("Invalid article queries");
        var fresh = new ArrayList<String>();
        for (String query : searchQueries) {
            if (query == null || query.isBlank() || query.codePointCount(0, query.length()) > 2000) throw new IllegalArgumentException("Invalid article query");
            if (queries.add(LawSearchQueryKey.of(query))) fresh.add(query);
        }
        if (fresh.isEmpty()) return Map.of("status", "DUPLICATE", "candidates", catalog());
        var before = evidence;
        evidence = searchBatch(fresh, evidence, ids, context, search, attempts, retained, searchProgress);
        return Map.of("status", evidence.isEmpty() ? "EMPTY" : changed(before, evidence) ? "AVAILABLE" : "NO_NEW_EVIDENCE",
                "candidates", catalog(), "capability", "Article index only; annexes are not indexed");
    }

    private List<Map<String, Object>> catalog() {
        return evidence.stream().map(item -> Map.<String, Object>of("id", item.id(), "title", item.title(),
                "sourceId", item.sourceId(), "sourceUrl", item.sourceUrl(),
                "estimatedBodyTokens", tokens.estimate(mapper.valueToTree(item).toString()), "read", read.containsKey(item.id()))).toList();
    }

    @Tool(name = "searchLaws", description = "Discover current laws in the official API using TITLE or BODY. Scans all result pages within server budgets and reranks candidates. Only returned approved candidate IDs can be passed to collectLaw. Metadata is not a citation. Change terms or scope if empty; do not repeat the same search.")
    public synchronized Map<String, Object> searchLaws(
            @ToolParam(description = "Legal keyword or law title, at most 200 characters") String query,
            @ToolParam(description = "TITLE or BODY") EvidenceDecision.SearchField field,
            @ToolParam(description = "Exact title match; false for BODY") boolean exactName,
            @ToolParam(description = "Optional numeric ministry code", required = false) String ministry) {
        begin("LAW_SEARCH");
        if (fixedIndexOnly) throw insufficient();
        var condition = new EvidenceDecision.LawSearch(query, field, exactName, ministry);
        if (!lawQueries.add(LawCollectionQueue.key(condition))) return Map.of("status", "DUPLICATE", "candidates", List.of());
        var observed = evidence.stream().map(EvidenceItem::provenance).filter(java.util.Objects::nonNull)
                .map(EvidenceProvenance::mst).collect(java.util.stream.Collectors.toSet());
        Map<String, LegalToolContracts.Candidate> pool = new LinkedHashMap<>();
        Map<String, Integer> scores = new HashMap<>();
        int page = 1;
        while (true) {
            var filter = new LawSearchFilters(null, null, LawSearchFilters.CURRENT, condition.exactName());
            var result = laws.searchLaws(context, new LegalToolContracts.SearchRequest(query,
                    field == EvidenceDecision.SearchField.TITLE ? 1 : 2, ministry, page, 20, filter));
            if (result.status() == LegalToolContracts.Status.BUDGET_EXHAUSTED)
                throw new LegalToolException(LegalToolException.Reason.BUDGET_EXHAUSTED, false);
            attempts.add(lawAttempt(condition, page, result));
            var unseen = result.candidates().stream().filter(c -> !collected.contains(c.id())
                    && !observed.contains(c.id()) && !pool.containsKey(c.id())).toList();
            observer.progress("CANDIDATE_REVIEW");
            var rankings = candidates.rank(question, plan, condition, unseen);
            unseen.forEach(c -> pool.put(c.id(), c));
            rankings.forEach(r -> scores.put(r.candidateId(), r.score()));
            checkActive();
            if (!result.hasNext()) break;
            if (++page > 10000) throw insufficient();
            observer.progress("LAW_SEARCH");
        }
        var selected = pool.values().stream().filter(c -> scores.getOrDefault(c.id(), 0) >= selection.getMinimumScore())
                .sorted(java.util.Comparator.comparingInt((LegalToolContracts.Candidate c) -> scores.get(c.id())).reversed())
                .limit(selection.getMaxCandidates()).toList();
        selected.forEach(c -> approved.put(c.id(), c));
        log.info("Law tool executionId={} stage=RERANK_COMPLETE pages={} candidates={} approved={}", executionId(), page, pool.size(), selected.size());
        return Map.of("status", selected.isEmpty() ? "EMPTY" : "AVAILABLE", "searchComplete", true, "pages", page, "candidates", selected);
    }

    @Tool(name = "collectLaw", description = "Collect an approved searchLaws candidate. The server fetches the immutable source, prevents duplicates, and incrementally embeds/indexes articles automatically. Reuses a verified published source when possible. This does not authorize citation: searchLawArticles then readLawArticles to obtain bodies.")
    public synchronized Map<String, Object> collectLaw(@ToolParam(description = "An approved candidate ID returned by searchLaws in this execution") String candidateId) {
        begin("LAW_DETAIL");
        if (fixedIndexOnly) throw insufficient();
        var candidate = approved.get(candidateId);
        if (candidate == null) throw new IllegalArgumentException("Unknown approved law candidate");
        if (collected.contains(candidateId)) return Map.of("status", "DUPLICATE");
        if (collected.size() >= 6) throw insufficient();
        collected.add(candidateId);
        var published = vector.publishedSource(candidate);
        if (published.isPresent()) {
            observer.progress("LAW_INDEX_REUSE");
            vector.reindexStoredLaw(context, published.get());
        } else {
            var detail = laws.getLawDetail(context, candidateId);
            if (!detail.contentAvailable()) return Map.of("status", "NO_CONTENT");
            observer.progress("LAW_INDEX");
            vector.vectorizeLaw(context, detail.referenceId());
        }
        // Newly indexed material makes prior empty queries meaningful again, but does not trigger a server-chosen search.
        queries.clear();
        return Map.of("status", "INDEXED", "next", "searchLawArticles with the relevant legal query, then readLawArticles");
    }

    @Tool(name = "readLawArticles", description = "Restore and verify full article bodies for 1-8 evidence UUIDs returned by searchLawArticles. Only successfully read IDs can be cited or selected in the final decision. The cumulative body token budget is enforced by the server; over-budget articles are reported without silently truncating them.")
    public synchronized Map<String, Object> readArticles(@ToolParam(description = "1-8 distinct authorized article evidence UUIDs") List<String> evidenceIds) {
        begin("DECIDING");
        if (evidenceIds == null || evidenceIds.isEmpty() || evidenceIds.size() > 8
                || evidenceIds.stream().distinct().count() != evidenceIds.size()
                || !evidence.stream().map(EvidenceItem::id).toList().containsAll(evidenceIds))
            throw new IllegalArgumentException("Unknown article read ID");
        var fresh = evidenceIds.stream().filter(id -> !read.containsKey(id)).toList();
        var outcomes = new ArrayList<Map<String, Object>>();
        for (var item : reader.readEvidence(evidence, fresh)) {
            int cost = tokens.estimate(mapper.valueToTree(item).toString());
            if (cost > bodyLimit - bodyTokens) outcomes.add(Map.of("id", item.id(), "status", "BODY_BUDGET_EXCEEDED"));
            else { bodyTokens += cost; read.put(item.id(), item); retained.add(item.id()); }
        }
        var bodies = evidenceIds.stream().filter(read::containsKey).map(read::get).toList();
        observer.evidence(evidence.stream().filter(item -> read.containsKey(item.id())).toList());
        return Map.of("status", bodies.isEmpty() ? "NO_BODY" : "READ", "evidence", bodies,
                "outcomes", outcomes, "remainingBodyTokens", bodyLimit - bodyTokens);
    }

    public CollectedLegalEvidence finish(EvidenceDecision decision) {
        checkActive();
        if (decision.evidenceIds().size() > 8) throw new IllegalArgumentException("Too many final evidence IDs");
        decision.validateAgainst(readIds());
        if (decision.action() == EvidenceDecision.Action.INSUFFICIENT) throw insufficient();
        if (decision.action() != EvidenceDecision.Action.ANSWER_READY && decision.action() != EvidenceDecision.Action.ANSWER_PARTIAL)
            throw new IllegalArgumentException("Use tools for search and collection, not action JSON");
        var chosen = decision.evidenceIds().stream().map(read::get).toList();
        observer.evidence(chosen);
        if (decision.action() == EvidenceDecision.Action.ANSWER_PARTIAL) {
            var guidance = new QuestionRetrievalPlan(plan.type(), QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE,
                    plan.searchQueries(), plan.lawSearches(), plan.missingFacts(), plan.analysis());
            return new CollectedLegalEvidence(guidance, chosen, decision.missingIssues());
        }
        return new CollectedLegalEvidence(plan, chosen);
    }

    private List<EvidenceItem> searchBatch(List<String> queries, List<EvidenceItem> before, Map<String, String> ids,
            LegalToolContext context, LawRagSearchToolService search, List<RetrievalAttempt> attempts, Set<String> retained,
            EvidenceSearchProgress progress) {
        List<List<LawSearchEvidence>> rankings = new ArrayList<>();
        for (String query : queries) {
            var hits = search.searchTop15(context, query);
            rankings.add(hits);
            progress.record(query, hits);
            var after = merge(hits, before, ids, Set.of());
            var outcome = hits.isEmpty() ? RetrievalAttempt.Outcome.NO_RESULTS : changed(before, after)
                    ? RetrievalAttempt.Outcome.AVAILABLE : RetrievalAttempt.Outcome.NO_NEW_EVIDENCE;
            attempts.add(new RetrievalAttempt(RetrievalAttempt.Kind.VECTOR_SEARCH, query, null, null,
                    outcome, hits.size(), false));
        }
        return merge(LawSearchResultFusion.fuse(rankings, progress.explicitUnits()), before,
                ids, retained, progress);
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
        return merge(hits, previous, ids, retained, null);
    }

    private List<EvidenceItem> merge(List<LawSearchEvidence> hits,
            List<EvidenceItem> previous, Map<String, String> ids, Set<String> retained,
            EvidenceSearchProgress progress) {
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
        if (progress != null)
            return EvidenceCandidateBalancer.select(List.copyOf(merged.values()),
                    progress.groups(), progress.explicitUnits(), retained, 15, 1024 * 1024);
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

    private static MolelawException insufficient() { return new MolelawException(ErrorCode.EVIDENCE_INSUFFICIENT); }
}
