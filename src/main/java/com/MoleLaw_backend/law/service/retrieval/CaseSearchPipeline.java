package com.MoleLaw_backend.law.service.retrieval;

import com.MoleLaw_backend.law.config.CaseSearchProperties;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.law.dto.retrieval.CaseSearchOutcome;
import com.MoleLaw_backend.law.dto.retrieval.LawCandidateSelection;
import com.MoleLaw_backend.law.dto.tool.LegalToolContracts;
import com.MoleLaw_backend.law.service.tool.CaseEvidenceReader;
import com.MoleLaw_backend.law.service.tool.LegalApiToolService;
import com.MoleLaw_backend.law.service.tool.LegalToolContext;
import com.MoleLaw_backend.law.exception.LegalToolException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Consumer;

/** Explicit law/fact routes, bounded body collection, then issue-based judgment reranking. */
@Service @RequiredArgsConstructor @Slf4j
public class CaseSearchPipeline {
    private final LegalApiToolService api;
    private final CaseEvidenceReader reader;
    private final CaseCandidateReranker reranker;
    private final CaseSearchProperties properties;
    private final CaseQueryPlanner queryPlanner;

    public int maxApiCalls() { return properties.getMaxApiCalls(); }

    public static final class State {
        private final Long roomId;
        public State() { this(null); }
        public State(Long roomId) { this.roomId = roomId; }
        public Long roomId() { return roomId; }
        private String userQuestion;
        private com.MoleLaw_backend.law.dto.retrieval.CaseQueryPlan searchPlan;
        public void userQuestion(String value) { userQuestion = value; }
        public com.MoleLaw_backend.law.dto.retrieval.CaseQueryPlan searchPlan() { return searchPlan; }
        private com.MoleLaw_backend.law.dto.retrieval.CaseSearchScope scope = com.MoleLaw_backend.law.dto.retrieval.CaseSearchScope.BODY;
        public void scope(com.MoleLaw_backend.law.dto.retrieval.CaseSearchScope value) { scope = value == null ? com.MoleLaw_backend.law.dto.retrieval.CaseSearchScope.BODY : value; }
        boolean currentSearchComplete = true;
        boolean currentRouteFailed;
        final Map<String, EvidenceItem> bodies = new LinkedHashMap<>();
        final Set<String> attempted = new HashSet<>();
        final Set<String> searches = new HashSet<>();
        final Set<String> plannedInputs = new HashSet<>();
        int rerankCalls;
    }

    public CaseSearchOutcome search(LegalToolContext context, EvidenceItem law, String query, int page,
            State state, Consumer<String> progress) {
        if (state.rerankCalls >= properties.getMaxRerankCalls()) return empty("BUDGET_EXHAUSTED", 0, 0, false, false, false);
        String inputKey = com.MoleLaw_backend.law.util.LawSearchQueryKey.of(query) + "|" + law.sourceId() + "|" + state.scope;
        if (!state.plannedInputs.add(inputKey)) return empty("DUPLICATE", 0, 0, false, false, false);
        context.checkDeadline();
        progress.accept("CASE_QUERY_PLANNING");
        com.MoleLaw_backend.law.dto.retrieval.CaseQueryPlan plan;
        try { plan = state.userQuestion == null ? queryPlanner.plan(query, law)
                : queryPlanner.plan(query, law, state.userQuestion, state.scope); }
        catch (com.MoleLaw_backend.common.ai.exception.GptApiException failure) {
            log.warn("판례처리 traceId={} 유형=후속 방={} 단계=검색어변환실패", context.executionId(), state.roomId);
            return empty("UNAVAILABLE", 0, 0, false, false, false).withSearchComplete(false);
        }
        context.checkDeadline();
        state.searchPlan = plan;
        if (plan.scope() != null) state.scope(plan.scope());
        log.info("판례처리 traceId={} 유형=후속 방={} 단계=의도판단 목적={} 대상수={} 검색범위={}",
                context.executionId(), state.roomId, plan.intent(), plan.targets().size(), state.scope);
        log.info("판례처리 traceId={} 유형=후속 방={} 단계=법률용어변환 생성질의={}", context.executionId(), state.roomId, plan.queries().size());
        CaseSearchOutcome outcome = empty("EMPTY", 0, 0, false, false, false);
        for (int i = 0; i < plan.queries().size(); i++) {
            log.info("판례처리 traceId={} 유형=후속 방={} 단계=법률용어검색 시도={}/{}", context.executionId(), state.roomId, i + 1, plan.queries().size());
            state.currentSearchComplete = true; state.currentRouteFailed = false;
            outcome = searchQuery(context, law, plan.queries().get(i), 1, state, progress, plan).withSearchComplete(state.currentSearchComplete);
            if (!outcome.candidates().isEmpty() || java.util.Set.of("UNAVAILABLE", "BUDGET_EXHAUSTED").contains(outcome.status())) return outcome;
        }
        return outcome;
    }

    private CaseSearchOutcome searchQuery(LegalToolContext context, EvidenceItem law, String query, int page,
            State state, Consumer<String> progress, com.MoleLaw_backend.law.dto.retrieval.CaseQueryPlan plan) {
        String lawName = law.title().replaceFirst("\\s+제[0-9]+조.*$", "").strip();
        String key = com.MoleLaw_backend.law.util.LawSearchQueryKey.of(query) + "|" + lawName + "|" + state.scope;
        if (!state.searches.add(key)) {
            log.info("판례처리 traceId={} 유형=후속 방={} 단계=검색생략 사유=동일조건반복", context.executionId(), state.roomId);
            return empty("DUPLICATE", 0, 0, false, false, false);
        }
        if (state.rerankCalls >= properties.getMaxRerankCalls()) {
            log.info("판례처리 traceId={} 유형=후속 방={} 단계=검색생략 사유=리랭크횟수소진", context.executionId(), state.roomId);
            return empty("BUDGET_EXHAUSTED", 0, 0, false, false, false);
        }
        boolean lawScope = state.scope == com.MoleLaw_backend.law.dto.retrieval.CaseSearchScope.LAW_REFERENCE;
        var request = new LegalToolContracts.SearchRequest(query, state.scope == com.MoleLaw_backend.law.dto.retrieval.CaseSearchScope.TITLE ? 1 : 2, null, 1, 100);
        progress.accept(lawScope ? "CASE_SEARCH_LAW" : state.scope == com.MoleLaw_backend.law.dto.retrieval.CaseSearchScope.TITLE ? "CASE_SEARCH_TITLE" : "CASE_SEARCH_FACT");
        var result = route(context, request, lawScope ? lawName : null, state.scope.name(), state);
        var lawResult = lawScope ? result : null;
        var factResult = lawScope ? null : result;
        List<LegalToolContracts.Candidate> lawItems = lawResult == null ? List.of() : lawResult.candidates();
        List<LegalToolContracts.Candidate> factItems = factResult == null ? List.of() : factResult.candidates();
        // Interleave both routes before the collection cap; no route silently consumes the entire budget.
        var merged = new LinkedHashMap<String, LegalToolContracts.Candidate>();
        for (int i = 0; i < Math.max(lawItems.size(), factItems.size()); i++) {
            if (i < lawItems.size()) merged.putIfAbsent(lawItems.get(i).id(), lawItems.get(i));
            if (i < factItems.size()) merged.putIfAbsent(factItems.get(i).id(), factItems.get(i));
        }
        boolean unavailableLaw = lawScope && (lawResult == null || state.currentRouteFailed || lawResult.status() == LegalToolContracts.Status.BUDGET_EXHAUSTED),
                unavailableFact = !lawScope && (factResult == null || state.currentRouteFailed || factResult.status() == LegalToolContracts.Status.BUDGET_EXHAUSTED);
        boolean hasNext = lawResult != null && lawResult.hasNext() || factResult != null && factResult.hasNext();
        log.info("판례처리 traceId={} 유형=후속 방={} 단계=후보병합 법령후보={} 사건후보={} 중복제외후={}",
                context.executionId(), state.roomId, lawItems.size(), factItems.size(), merged.size());
        if (merged.isEmpty()) {
            boolean budget = lawResult != null && lawResult.status() == LegalToolContracts.Status.BUDGET_EXHAUSTED
                    || factResult != null && factResult.status() == LegalToolContracts.Status.BUDGET_EXHAUSTED;
            String status = budget ? "BUDGET_EXHAUSTED" : unavailableLaw || unavailableFact ? "UNAVAILABLE" : "EMPTY";
            log.info("판례처리 traceId={} 유형=후속 방={} 단계=검색완료 상태={} 최종채택=0", context.executionId(), state.roomId, status);
            return empty(status, lawItems.size(), factItems.size(), unavailableLaw, unavailableFact, hasNext);
        }
        List<LegalToolContracts.Candidate> ordered = merged.values().stream().sorted(Comparator.comparingInt((LegalToolContracts.Candidate candidate) ->
                com.MoleLaw_backend.law.util.LawSearchQueryKey.of(candidate.title()).contains(com.MoleLaw_backend.law.util.LawSearchQueryKey.of(query)) ? 1 : 0).reversed()).toList();
        if (plan.intent() == com.MoleLaw_backend.law.dto.retrieval.CaseQueryPlan.Intent.SPECIFIC_CASE) {
            progress.accept("CASE_CANDIDATE_SELECTION");
            if (ordered.size() > 100) state.currentSearchComplete = false;
            context.checkDeadline();
            List<String> chosen;
            try { chosen = reranker.selectForReading(plan, law, ordered.stream().limit(100).toList(), properties.getDetailsPerSearch()); }
            catch (com.MoleLaw_backend.common.ai.exception.GptApiException failure) {
                state.currentSearchComplete = false;
                log.warn("판례처리 traceId={} 유형=후속 방={} 단계=후보선정실패 사유={}",
                        context.executionId(), state.roomId, failure.getErrorCode());
                return empty("UNAVAILABLE", lawItems.size(), factItems.size(), false, false, hasNext).withSearchComplete(false);
            }
            context.checkDeadline();
            log.info("판례처리 traceId={} 유형=후속 방={} 단계=읽기후보선정 목록후보={} 본문후보={}",
                    context.executionId(), state.roomId, merged.size(), chosen.size());
            ordered = chosen.stream().map(merged::get).toList();
        }
        progress.accept("CASE_BODY_REVIEW");
        var bodies = new ArrayList<EvidenceItem>();
        int fetched = 0;
        boolean bodyFailure = false;
        for (var candidate : ordered) {
            var cached = state.bodies.get(candidate.id());
            if (cached != null) { bodies.add(cached); continue; }
            if (fetched >= properties.getDetailsPerSearch() || state.attempted.size() >= properties.getMaxDetails()) continue;
            if (!state.attempted.add(candidate.id())) continue;
            fetched++;
            try {
                var detail = api.getCaseDetail(context, candidate.id());
                if (detail == null || !detail.contentAvailable()) {
                    log.info("판례처리 traceId={} 유형=후속 방={} 단계=본문제외 판례={} 사유=NO_BODY", context.executionId(), state.roomId, candidate.id());
                    continue;
                }
                var item = reader.read(detail.referenceId(), UUID.randomUUID().toString());
                if (!candidate.id().equals(item.sourceId()) || !"CASE_BODY".equals(item.kind())) throw new IllegalArgumentException("Case identity mismatch");
                state.bodies.put(candidate.id(), item);
                bodies.add(item);
                log.info("판례처리 traceId={} 유형=후속 방={} 단계=본문확보 판례={} 글자수={}", context.executionId(), state.roomId, candidate.id(), item.content().length());
            } catch (LegalToolException failure) {
                bodyFailure = true;
                log.warn("판례처리 traceId={} 유형=후속 방={} 단계=본문실패 판례={} 사유={}", context.executionId(), state.roomId, candidate.id(), failure.getReason());
            }
        }
        if (bodies.isEmpty()) return new CaseSearchOutcome(bodyFailure ? "UNAVAILABLE" : state.attempted.size() >= properties.getMaxDetails() ? "BUDGET_EXHAUSTED" : "NO_READABLE_BODY",
                List.of(), Map.of(), List.of(), lawItems.size(), factItems.size(), merged.size(), unavailableLaw, unavailableFact, hasNext);
        if (state.rerankCalls >= properties.getMaxRerankCalls()) return new CaseSearchOutcome("BUDGET_EXHAUSTED",
                List.of(), Map.of(), List.of(), lawItems.size(), factItems.size(), merged.size(), unavailableLaw, unavailableFact, hasNext);
        context.checkDeadline();
        progress.accept("CASE_RERANK");
        state.rerankCalls++;
        List<LawCandidateSelection.Ranking> rankings;
        try { rankings = plan.scope() != null || plan.intent() == com.MoleLaw_backend.law.dto.retrieval.CaseQueryPlan.Intent.SPECIFIC_CASE
                ? reranker.rank(query, law, bodies, plan) : reranker.rank(query, law, bodies); }
        catch (com.MoleLaw_backend.common.ai.exception.GptApiException failure) {
            log.warn("판례처리 traceId={} 유형=후속 방={} 단계=리랭크실패 사유={}", context.executionId(), state.roomId, failure.getErrorCode());
            return new CaseSearchOutcome("UNAVAILABLE", List.of(), Map.of(), List.of(), lawItems.size(), factItems.size(), merged.size(), unavailableLaw, unavailableFact, hasNext);
        }
        context.checkDeadline();
        Set<String> selectedIds = rankings.stream().filter(r -> r.score() >= properties.getMinimumScore())
                .sorted(Comparator.comparingInt(LawCandidateSelection.Ranking::score).reversed())
                .limit(properties.getMaxSelected()).map(LawCandidateSelection.Ranking::candidateId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (var rank : rankings) log.info("판례처리 traceId={} 유형=후속 방={} 단계=리랭크 판례={} 점수={} 기준={} 결과={} 사유={}",
                context.executionId(), state.roomId, rank.candidateId(), rank.score(), properties.getMinimumScore(), selectedIds.contains(rank.candidateId()) ? "채택" : "제외",
                selectedIds.contains(rank.candidateId()) ? "기준충족" : rank.score() < properties.getMinimumScore() ? "관련성기준미달" : "상위개수제한");
        var selected = rankings.stream().filter(r -> selectedIds.contains(r.candidateId()))
                .sorted(Comparator.comparingInt(LawCandidateSelection.Ranking::score).reversed())
                .map(r -> merged.get(r.candidateId())).toList();
        var selectedBodies = new LinkedHashMap<String, EvidenceItem>();
        selectedIds.forEach(id -> selectedBodies.put(id, state.bodies.get(id)));
        String status = selected.isEmpty() ? "NO_RELEVANT_CASES" : unavailableLaw || unavailableFact || bodyFailure || !state.currentSearchComplete ? "PARTIAL_AVAILABLE" : "AVAILABLE";
        log.info("판례처리 traceId={} 유형=후속 방={} 단계=검색완료 상태={} 본문검토={} 최종채택={} API호출={}",
                context.executionId(), state.roomId, status, bodies.size(), selected.size(), context.callsUsed());
        return new CaseSearchOutcome(status, selected, selectedBodies, rankings, lawItems.size(), factItems.size(), merged.size(), unavailableLaw, unavailableFact, hasNext);
    }

    private LegalToolContracts.SearchResult route(LegalToolContext context, LegalToolContracts.SearchRequest request, String lawName, String route, State state) {
        var collected = new LinkedHashMap<String, LegalToolContracts.Candidate>();
        int page = 1, total = 0;
        String fingerprint;
        try { fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(request.query().getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 12); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        while (true) {
            if (!context.hasBudget()) { state.currentSearchComplete = false; break; }
            var next = new LegalToolContracts.SearchRequest(request.query(), request.search(), null, page, 100);
            LegalToolContracts.SearchResult result;
            try { result = lawName == null ? api.searchCases(context, next) : api.searchCasesForLaw(context, next, lawName); }
            catch (LegalToolException failure) {
                state.currentSearchComplete = false; state.currentRouteFailed = true;
                log.warn("판례처리 traceId={} 유형=후속 방={} 단계=검색실패 경로={} 페이지={} 사유={}", context.executionId(), state.roomId, route, page, failure.getReason());
                if (collected.isEmpty()) return null;
                break;
            }
            if (result == null) { state.currentSearchComplete = false; state.currentRouteFailed = true; return null; }
            total = result.totalCount();
            for (var candidate : result.candidates()) {
                if (collected.size() >= properties.getMaxCandidates() && !collected.containsKey(candidate.id())) {
                    state.currentSearchComplete = false; break;
                }
                collected.putIfAbsent(candidate.id(), candidate);
            }
            log.info("판례처리 traceId={} 유형=후속 방={} 단계=페이지수집 경로={} 검색범위={} 법령필터={} 질의지문={} 페이지={} 전체={} 페이지후보={} 누적={} 다음페이지={}",
                    context.executionId(), state.roomId, route, request.search(), lawName == null ? "없음" : lawName, fingerprint,
                    page, total, result.candidates().size(), collected.size(), result.hasNext());
            if (!state.currentSearchComplete) break;
            if (result.status() == LegalToolContracts.Status.BUDGET_EXHAUSTED) { state.currentSearchComplete = false; break; }
            if (!result.hasNext()) break;
            if (result.candidates().isEmpty() || page >= 10000) { state.currentSearchComplete = false; break; }
            page++;
        }
        log.info("판례처리 traceId={} 유형=후속 방={} 단계=목록수집완료 경로={} 전체수집완료={} 수집={} 전체={}",
                context.executionId(), state.roomId, route, state.currentSearchComplete, collected.size(), total);
        return new LegalToolContracts.SearchResult(collected.isEmpty()
                ? state.currentSearchComplete ? LegalToolContracts.Status.EMPTY : LegalToolContracts.Status.BUDGET_EXHAUSTED
                : LegalToolContracts.Status.AVAILABLE, List.copyOf(collected.values()), page, 100, total,
                !state.currentSearchComplete, java.time.Instant.now(), "https://www.law.go.kr/DRF/lawSearch.do?target=prec");
    }

    private CaseSearchOutcome empty(String status, int law, int facts, boolean lawFailed, boolean factFailed, boolean next) {
        return new CaseSearchOutcome(status, List.of(), Map.of(), List.of(), law, facts, 0, lawFailed, factFailed, next);
    }
}
