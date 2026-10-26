package com.MoleLaw_backend.law.service.retrieval;

import com.MoleLaw_backend.law.config.CaseSearchProperties;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.law.dto.retrieval.LawCandidateSelection;
import com.MoleLaw_backend.law.dto.tool.LegalToolContracts.*;
import com.MoleLaw_backend.law.service.tool.*;
import com.MoleLaw_backend.law.exception.LegalToolException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CaseSearchPipelineTest {
    @Test void modelSelectsLaterNamedCaseBeforeDetailAndOverridesSuggestedScope() {
        var plan = new com.MoleLaw_backend.law.dto.retrieval.CaseQueryPlan(List.of("삼풍백화점"),
                com.MoleLaw_backend.law.dto.retrieval.CaseQueryPlan.Intent.SPECIFIC_CASE, List.of("삼풍백화점"),
                com.MoleLaw_backend.law.dto.retrieval.CaseSearchScope.BODY);
        state.scope(com.MoleLaw_backend.law.dto.retrieval.CaseSearchScope.LAW_REFERENCE);
        state.userQuestion("삼풍백화점 판결을 찾아줘");
        when(queryPlanner.plan(anyString(), any(), anyString(), any())).thenReturn(plan);
        var rows = java.util.stream.IntStream.rangeClosed(1, 11).mapToObj(i ->
                new Candidate("" + (100 + i), null, "공식 사건명" + i, null, "사건번호" + i, "19960823", "대법원")).toList();
        when(api.searchCases(any(), any())).thenReturn(new SearchResult(Status.AVAILABLE, rows, 1, 100, 11, false,
                Instant.now(), "https://www.law.go.kr"));
        when(reranker.selectForReading(eq(plan), eq(law), anyList(), eq(4))).thenReturn(List.of("108"));
        when(reranker.rank(anyString(), any(), anyList(), eq(plan))).thenReturn(List.of(new LawCandidateSelection.Ranking("108", 95, "지정 사건 본문")));
        var outcome = pipeline.search(context, law, "일반 구조안전 쟁점", 1, state, s -> {});
        assertThat(outcome.candidates()).extracting(Candidate::id).containsExactly("108");
        verify(api).getCaseDetail(any(), eq("108"));
        verify(api, never()).getCaseDetail(any(), eq("101"));
        verify(api, never()).searchCasesForLaw(any(), any(), anyString());
        verify(reranker).rank(eq("삼풍백화점"), eq(law), anyList(), eq(plan));
    }
    @Test void titleAndLawReferenceAreExplicitToolChoicesRatherThanAutomaticBranches() {
        when(api.searchCases(any(), any())).thenReturn(result("101"));
        state.scope(com.MoleLaw_backend.law.dto.retrieval.CaseSearchScope.TITLE);
        pipeline.search(context, law, "연차", 1, state, s -> {});
        verify(api).searchCases(any(), argThat(r -> r.search() == 1));
        verify(api, never()).searchCasesForLaw(any(), any(), anyString());
        var referenceState = new CaseSearchPipeline.State();
        referenceState.scope(com.MoleLaw_backend.law.dto.retrieval.CaseSearchScope.LAW_REFERENCE);
        when(api.searchCasesForLaw(any(), any(), anyString())).thenReturn(result("102"));
        pipeline.search(context, law, "연차", 1, referenceState, s -> {});
        verify(api).searchCasesForLaw(any(), argThat(r -> r.search() == 2), eq("근로기준법"));
    }

    @Test void selectedScopeCollectsEveryPageBeforeBodyReranking() {
        var first = result("101", "102");
        when(api.searchCases(any(), any())).thenReturn(new SearchResult(Status.AVAILABLE, first.candidates(), 1, 100, 3, true, Instant.now(), first.sourceUrl()), result("103"));
        var outcome = pipeline.search(context, law, "연차", 1, state, s -> {});
        assertThat(outcome.uniqueCount()).isEqualTo(3);
        assertThat(outcome.searchComplete()).isTrue();
        verify(api).searchCases(any(), argThat(r -> r.page() == 1 && r.display() == 100));
        verify(api).searchCases(any(), argThat(r -> r.page() == 2 && r.display() == 100));
        verify(reranker).rank(anyString(), any(), argThat(bodies -> bodies.size() == 3));
    }

    @Test void candidateCapExplicitlyMarksListCollectionIncomplete() {
        properties.setMaxCandidates(2);
        when(api.searchCases(any(), any())).thenReturn(result("101", "102", "103"));
        var outcome = pipeline.search(context, law, "연차", 1, state, s -> {});
        assertThat(outcome.searchComplete()).isFalse();
        assertThat(outcome.uniqueCount()).isEqualTo(2);
    }
    @Test void legalQueryVariantsAreExecutedAutomaticallyAfterEmptyResults() {
        doReturn(new com.MoleLaw_backend.law.dto.retrieval.CaseQueryPlan(List.of("연차휴가", "연차수당", "사용촉진"))).when(queryPlanner).plan(anyString(), any());
        when(api.searchCasesForLaw(any(), any(), anyString())).thenReturn(result());
        when(api.searchCases(any(), any())).thenReturn(result(), result("101"));
        var outcome = pipeline.search(context, law, "일이 바빠서 휴가를 못 썼어요", 1, state, s -> {});
        assertThat(outcome.candidates()).hasSize(1);
        verify(api).searchCases(any(), argThat(r -> r.query().equals("연차휴가")));
        verify(api).searchCases(any(), argThat(r -> r.query().equals("연차수당")));
        verify(api, never()).searchCases(any(), argThat(r -> r.query().equals("사용촉진")));
    }
    private final LegalApiToolService api = mock(LegalApiToolService.class);
    private final CaseEvidenceReader reader = mock(CaseEvidenceReader.class);
    private final CaseCandidateReranker reranker = mock(CaseCandidateReranker.class);
    private final CaseSearchProperties properties = new CaseSearchProperties();
    private final CaseQueryPlanner queryPlanner = mock(CaseQueryPlanner.class);
    private final CaseSearchPipeline pipeline = new CaseSearchPipeline(api, reader, reranker, properties, queryPlanner);
    private final EvidenceItem law = new EvidenceItem("law-id", "LAW_CHUNK", "285279:61", "근로기준법 제61조(사용 촉진)", "https://www.law.go.kr", "법령 본문");
    private final CaseSearchPipeline.State state = new CaseSearchPipeline.State();
    private final LegalToolContext context = new LegalToolContext(LegalToolContext.Purpose.FOLLOW_UP_CASE, "test-trace",
            Instant.now().plusSeconds(90), 16, Clock.systemUTC());

    @BeforeEach void bodies() {
        when(queryPlanner.plan(anyString(), any())).thenAnswer(call -> new com.MoleLaw_backend.law.dto.retrieval.CaseQueryPlan(List.of((String) call.getArgument(0))));
        when(api.getCaseDetail(any(), anyString())).thenAnswer(call -> new DetailResult("source-" + call.getArgument(1), call.getArgument(1), null,
                null, "판례", 0, true, Instant.now(), "https://www.law.go.kr", List.of()));
        when(reader.read(anyString(), anyString())).thenAnswer(call -> new EvidenceItem(call.getArgument(1), "CASE_BODY",
                ((String) call.getArgument(0)).substring(7), "판례", "https://www.law.go.kr", "판결 이유 본문"));
        when(reranker.rank(anyString(), any(), anyList())).thenAnswer(call -> ((List<EvidenceItem>) call.getArgument(2)).stream()
                .map(item -> new LawCandidateSelection.Ranking(item.sourceId(), 90, "쟁점 관련")).toList());
    }

    private SearchResult result(String... ids) {
        return new SearchResult(ids.length == 0 ? Status.EMPTY : Status.AVAILABLE,
                Arrays.stream(ids).map(id -> new Candidate(id, null, "임금", null, "사건번호", "20260101", "대법원")).toList(),
                1, 10, ids.length, false, Instant.now(), "https://www.law.go.kr");
    }

    @Test void defaultBodyScopeRanksCollectedBodiesWithoutAutomaticallyApplyingLawFilter() {
        when(api.searchCasesForLaw(any(), any(), eq("근로기준법"))).thenReturn(result("101", "102"));
        when(api.searchCases(any(), any())).thenReturn(result("102", "103"));
        when(reranker.rank(anyString(), any(), anyList())).thenReturn(List.of(
                new LawCandidateSelection.Ranking("101", 20, "다른 쟁점"), new LawCandidateSelection.Ranking("102", 95, "직접 관련"),
                new LawCandidateSelection.Ranking("103", 80, "관련")));
        var stages = new ArrayList<String>();
        var outcome = pipeline.search(context, law, "연차 사용촉진", 1, state, stages::add);
        assertThat(outcome.status()).isEqualTo("AVAILABLE");
        assertThat(outcome.uniqueCount()).isEqualTo(2);
        assertThat(outcome.candidates()).extracting(Candidate::id).containsExactly("102", "103");
        assertThat(outcome.bodies()).containsOnlyKeys("102", "103");
        verify(api, times(2)).getCaseDetail(any(), anyString());
        verify(reranker).rank(eq("연차 사용촉진"), eq(law), argThat(items -> items.size() == 2 && items.stream().allMatch(e -> e.content().contains("판결 이유"))));
        assertThat(stages).containsExactly("CASE_QUERY_PLANNING", "CASE_SEARCH_FACT", "CASE_BODY_REVIEW", "CASE_RERANK");
    }

    @Test void factRouteCanFindCandidatesWhenLawFilteredRouteIsEmpty() {
        when(api.searchCasesForLaw(any(), any(), anyString())).thenReturn(result());
        when(api.searchCases(any(), any())).thenReturn(result("101"));
        var outcome = pipeline.search(context, law, "연차휴가", 1, state, s -> {});
        assertThat(outcome.lawCount()).isZero();
        assertThat(outcome.factCount()).isEqualTo(1);
        assertThat(outcome.candidates()).hasSize(1);
    }

    @Test void selectedScopeOutageIsReportedAndNeverMasqueradesAsEmpty() {
        when(api.searchCasesForLaw(any(), any(), anyString())).thenThrow(new LegalToolException(LegalToolException.Reason.API_FAILURE, true));
        when(api.searchCases(any(), any())).thenThrow(new LegalToolException(LegalToolException.Reason.API_FAILURE, true));
        var outcome = pipeline.search(context, law, "연차휴가", 1, state, s -> {});
        assertThat(outcome.status()).isEqualTo("UNAVAILABLE");
        assertThat(outcome.factUnavailable()).isTrue();
        assertThat(outcome.candidates()).isEmpty();
    }

    @Test void irrelevantBodiesAreExcludedAndDuplicateQueriesDoNotSpendApiCalls() {
        when(api.searchCasesForLaw(any(), any(), anyString())).thenReturn(result("101"));
        when(api.searchCases(any(), any())).thenReturn(result("101"));
        when(reranker.rank(anyString(), any(), anyList())).thenReturn(List.of(new LawCandidateSelection.Ranking("101", 10, "다른 쟁점")));
        assertThat(pipeline.search(context, law, "연차휴가", 1, state, s -> {}).status()).isEqualTo("NO_RELEVANT_CASES");
        assertThat(pipeline.search(context, law, "연차 휴가!", 1, state, s -> {}).status()).isEqualTo("DUPLICATE");
        verify(api).searchCases(any(), any());
        verify(api, never()).searchCasesForLaw(any(), any(), anyString());
    }

    @Test void bothRoutesEmptyNeverCallsDetailOrReranker() {
        when(api.searchCasesForLaw(any(), any(), anyString())).thenReturn(result());
        when(api.searchCases(any(), any())).thenReturn(result());
        assertThat(pipeline.search(context, law, "쟁점", 1, state, s -> {}).status()).isEqualTo("EMPTY");
        verify(api, never()).getCaseDetail(any(), anyString());
        verifyNoInteractions(reranker);
    }

    @Test void exhaustedApiBudgetIsNotMisreportedAsNoCases() {
        var exhausted = new SearchResult(Status.BUDGET_EXHAUSTED, List.of(), 1, 10, 0, false, Instant.now(), "https://www.law.go.kr");
        when(api.searchCasesForLaw(any(), any(), anyString())).thenReturn(exhausted);
        when(api.searchCases(any(), any())).thenReturn(exhausted);
        assertThat(pipeline.search(context, law, "쟁점", 1, state, s -> {}).status()).isEqualTo("BUDGET_EXHAUSTED");
        verifyNoInteractions(reranker);
    }

    @Test void rerankFailureNeverAuthorizesUnrankedCandidateBodies() {
        when(api.searchCasesForLaw(any(), any(), anyString())).thenReturn(result("101"));
        when(api.searchCases(any(), any())).thenReturn(result("101"));
        when(reranker.rank(anyString(), any(), anyList())).thenThrow(new com.MoleLaw_backend.common.ai.exception.GptApiException(
                com.MoleLaw_backend.common.exception.ErrorCode.GPT_API_FAILURE));
        var outcome = pipeline.search(context, law, "쟁점", 1, state, s -> {});
        assertThat(outcome.status()).isEqualTo("UNAVAILABLE");
        assertThat(outcome.candidates()).isEmpty();
        assertThat(outcome.bodies()).isEmpty();
    }

    @Test void detailAndRerankBudgetsAreBoundedAcrossQueryRevisions() {
        when(api.searchCasesForLaw(any(), any(), anyString())).thenReturn(result("101", "102", "103", "104"), result("105", "106", "107", "108"));
        when(api.searchCases(any(), any())).thenReturn(result("101", "102", "103", "104"), result("105", "106", "107", "108"));
        pipeline.search(context, law, "첫 쟁점", 1, state, s -> {});
        pipeline.search(context, law, "다른 쟁점", 1, state, s -> {});
        assertThat(pipeline.search(context, law, "세번째 쟁점", 1, state, s -> {}).status()).isEqualTo("BUDGET_EXHAUSTED");
        verify(api, times(6)).getCaseDetail(any(), anyString());
        verify(reranker, times(2)).rank(anyString(), any(), anyList());
    }
}
