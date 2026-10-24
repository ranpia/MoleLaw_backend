package com.MoleLaw_backend.law.service.retrieval;
import com.MoleLaw_backend.law.dto.vector.LawSearchEvidence;
import com.MoleLaw_backend.law.dto.tool.LegalToolContracts;
import com.MoleLaw_backend.law.exception.LegalToolException;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.law.dto.retrieval.EvidenceDecision;
import com.MoleLaw_backend.law.dto.request.LawSearchFilters;
import com.MoleLaw_backend.law.service.retrieval.EvidenceDecisionPlanner;
import com.MoleLaw_backend.law.service.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.MoleLaw_backend.law.dto.retrieval.QuestionRetrievalPlan;
import com.MoleLaw_backend.law.dto.retrieval.RetrievalAttempt;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegalEvidenceCollectorTest {
    @Test void observerPublishesOnlyAdoptedEvidenceAfterDecisionInsteadOfSearchCandidates() {
        var first = hit();
        var second = new LawSearchEvidence("123:2", first.referenceId(), first.externalId(), first.lawId(),
                first.effectiveDate(), "다른 조문", "다른 본문", "다른 전체 본문", first.sourceUrl(), .7,
                first.modelRevision(), first.configFingerprint(), first.generation());
        when(rag.searchTop15(any(), anyString())).thenReturn(List.of(first, second));
        var published = new java.util.ArrayList<List<EvidenceItem>>();
        var stages = new java.util.ArrayList<String>();
        var observer = new com.MoleLaw_backend.law.dto.retrieval.RetrievalObserver() {
            @Override public void progress(String stage) { stages.add(stage); }
            @Override public void evidence(List<EvidenceItem> confirmed) { published.add(confirmed); }
        };
        when(planner.decide(anyString(), anyList(), any(), anyList(), anyInt())).thenAnswer(call -> {
            assertThat(published).isEmpty();
            List<EvidenceItem> supplied = call.getArgument(1);
            return new EvidenceDecision(EvidenceDecision.Action.ANSWER_READY, "한 조문으로 충분",
                    List.of(), List.of(supplied.get(0).id()), List.of(), List.of());
        });
        var result = collector.collectForAnswer("질문", observer);
        assertThat(stages).containsSubsequence("QUESTION_PLANNING", "INITIAL_SEARCH", "DECIDING");
        assertThat(published).hasSize(1);
        assertThat(published.get(0)).isEqualTo(result.evidence()).hasSize(1);
    }

    @Test void previouslyApprovedIssueCoverageSurvivesNewRankings() {
        var first = new java.util.ArrayList<LawSearchEvidence>();
        var fresh = new java.util.ArrayList<LawSearchEvidence>();
        for (int article = 1; article <= 17; article++) {
            var value = new LawSearchEvidence("123:" + article, "00000000-0000-0000-0000-000000000001", "123", "1",
                    "20260101", "법령 제" + article + "조", "조문", "전체 본문", "https://www.law.go.kr", .8,
                    "revision", "a".repeat(64), "00000000-0000-0000-0000-000000000002");
            if (article <= 10) first.add(value);
            if (article >= 3) fresh.add(value);
        }
        when(rag.searchTop15(any(), eq("질문"))).thenReturn(first);
        when(rag.searchTop15(any(), eq("추가 쟁점"))).thenReturn(fresh);
        when(planner.decide(anyString(), anyList(), any(), anyList(), anyInt()))
                .thenAnswer(call -> {
                    List<EvidenceItem> supplied = call.getArgument(1);
                    return new EvidenceDecision(EvidenceDecision.Action.SEARCH_AGAIN, "추가 쟁점 확인",
                            List.of("추가 쟁점"), supplied.stream().map(EvidenceItem::id).toList(), List.of("추가 쟁점"), List.of());
                }).thenAnswer(call -> {
                    List<EvidenceItem> supplied = call.getArgument(1);
                    assertThat(supplied).extracting(EvidenceItem::sourceId).contains("123:1", "123:2");
                    return ready(supplied);
                });
        assertThat(collector.collect("질문")).hasSize(15);
    }

    @Test void partialDecisionReturnsSupportedEvidenceAsGeneralGuidance() {
        when(questions.plan("질문")).thenReturn(new QuestionRetrievalPlan(QuestionRetrievalPlan.Type.CASE_ASSESSMENT,
                QuestionRetrievalPlan.ResponseScope.SPECIFIC_ASSESSMENT, List.of("질문"), List.of(), List.of()));
        when(rag.searchTop15(any(), anyString())).thenReturn(List.of(hit()));
        when(planner.decide(anyString(), anyList(), any(), anyList(), anyInt())).thenAnswer(call -> {
            List<EvidenceItem> supplied = call.getArgument(1);
            return new EvidenceDecision(EvidenceDecision.Action.ANSWER_PARTIAL, "확인된 조문으로 일반 안내 가능",
                    List.of("별표 미확인"), List.of(supplied.get(0).id()), List.of(), List.of());
        });
        var result = collector.collectForAnswer("질문");
        assertThat(result.plan().responseScope()).isEqualTo(QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE);
        assertThat(result.unresolvedIssues()).containsExactly("별표 미확인");
        assertThat(result.evidence()).hasSize(1);
        verifyNoInteractions(laws, vectors);
    }

    @Test void punctuationOnlyQueryChangesAreSkippedAndReportedToPlanner() {
        when(rag.searchTop15(any(), anyString())).thenReturn(List.of(hit()));
        when(planner.decide(anyString(), anyList(), any(), anyList(), anyInt()))
                .thenAnswer(call -> new EvidenceDecision(EvidenceDecision.Action.SEARCH_AGAIN, "추가 확인",
                        List.of("쟁점"), List.of(), List.of("질 문!"), List.of()))
                .thenAnswer(call -> {
                    List<RetrievalAttempt> attempts = call.getArgument(3);
                    assertThat(attempts).anyMatch(a -> a.outcome() == RetrievalAttempt.Outcome.DUPLICATE);
                    return ready(call.getArgument(1));
                });
        assertThat(collector.collect("질문")).hasSize(1);
        verify(rag, times(1)).searchTop15(any(), anyString());
    }

    @Test void multiQueryBatchKeepsTopArticlesFromAllThreeQueriesInsteadOfOnlyTheLastOne() {
        when(questions.plan("질문")).thenReturn(new QuestionRetrievalPlan(QuestionRetrievalPlan.Type.LAW_EXPLANATION,
                QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE, List.of("첫 쟁점", "두번째 쟁점", "세번째 쟁점"), List.of(), List.of()));
        for (int group = 0; group < 3; group++) {
            var hits = new java.util.ArrayList<LawSearchEvidence>();
            for (int i = 1; i <= 15; i++) {
                int article = group * 15 + i;
                hits.add(new LawSearchEvidence("123:" + article, "00000000-0000-0000-0000-000000000001", "123", "1",
                        "20260101", "법령 제" + article + "조", "조문", "전체 본문", "https://www.law.go.kr", .8,
                        "revision", "a".repeat(64), "00000000-0000-0000-0000-000000000002"));
            }
            when(rag.searchTop15(any(), eq(List.of("첫 쟁점", "두번째 쟁점", "세번째 쟁점").get(group)))).thenReturn(hits);
        }
        when(planner.decide(anyString(), anyList(), any(), anyList(), anyInt())).thenAnswer(call -> ready(call.getArgument(1)));
        assertThat(collector.collect("질문")).hasSize(15).extracting(EvidenceItem::sourceId).contains("123:1", "123:16", "123:31");
    }
    private final LawRagSearchToolService rag = mock(LawRagSearchToolService.class);
    private final LawVectorizationToolService vectors = mock(LawVectorizationToolService.class);
    private final LegalApiToolService laws = mock(LegalApiToolService.class);
    private final EvidenceDecisionPlanner planner = mock(EvidenceDecisionPlanner.class);
    private final QuestionRetrievalPlanner questions = mock(QuestionRetrievalPlanner.class);
    private final LawCandidateSelector candidates = mock(LawCandidateSelector.class);
    private final LegalEvidenceCollector collector = new LegalEvidenceCollector(provider(rag), provider(vectors), laws, planner, questions, candidates, mock(AiSearchEvidenceCollector.class), com.MoleLaw_backend.law.dto.retrieval.RetrievalBackend.QDRANT, new com.MoleLaw_backend.law.config.LawCandidateSelectionProperties());

    @BeforeEach void classify() {
        when(candidates.rank(anyString(), any(), any(), anyList())).thenAnswer(call -> ((List<LegalToolContracts.Candidate>) call.getArgument(3)).stream().map(c -> new com.MoleLaw_backend.law.dto.retrieval.LawCandidateSelection.Ranking(c.id(), 95, "관련")).toList());
        when(questions.plan("질문")).thenReturn(new QuestionRetrievalPlan(QuestionRetrievalPlan.Type.LAW_EXPLANATION,
                QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE, List.of("질문"), List.of(), List.of()));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
    private LawSearchEvidence hit() {
        return new LawSearchEvidence("123:1", UUID.randomUUID().toString(), "123", "001823",
                "20260101", "민법 제1조", "조문", "민사에 관하여 적용한다.", "https://www.law.go.kr/법령?lsiSeq=123", .9,
                "revision", "a".repeat(64), UUID.randomUUID().toString());
    }
    private EvidenceDecision ready(List<EvidenceItem> evidence) {
        return new EvidenceDecision(EvidenceDecision.Action.ANSWER_READY, "근거 확보", List.of(),
                evidence.stream().map(EvidenceItem::id).toList(), List.of(), List.of());
    }
    private EvidenceDecision collect() {
        return new EvidenceDecision(EvidenceDecision.Action.COLLECT_LAW, "민법 원문 필요", List.of("적용 조문"),
                List.of(), List.of(), List.of(new EvidenceDecision.LawSearch("민법", EvidenceDecision.SearchField.TITLE, true, null)));
    }

    @Test void previouslyRetrievedLawIsExcludedBeforeRerankingAndDetail() {
        when(rag.searchTop15(any(), anyString())).thenReturn(List.of(hit()));
        when(planner.decide(anyString(), anyList(), any(), anyList(), anyInt())).thenReturn(collect());
        var candidate = new LegalToolContracts.Candidate("123", "001823", "민법", "20260101", null, null, null);
        when(laws.searchLaws(any(), any())).thenReturn(new LegalToolContracts.SearchResult(
                LegalToolContracts.Status.AVAILABLE, List.of(candidate), 1, 20, 1, false,
                Instant.now(), "https://www.law.go.kr"));
        assertThatThrownBy(() -> collector.collect("질문")).isInstanceOf(MolelawException.class);
        verify(laws, never()).getLawDetail(any(), anyString());
        verifyNoInteractions(vectors);
    }

    @Test void publishedVersionUsesVerifiedStoredSourceWithoutAnotherDetailCall() {
        when(rag.searchTop15(any(), anyString())).thenReturn(List.of(), List.of(hit()));
        when(planner.decide(anyString(), anyList(), any(), anyList(), anyInt()))
                .thenReturn(collect()).thenAnswer(call -> ready(call.getArgument(1)));
        var candidate = new LegalToolContracts.Candidate("123", "001823", "민법", "20260101", null, null, null);
        when(laws.searchLaws(any(), any())).thenReturn(new LegalToolContracts.SearchResult(
                LegalToolContracts.Status.AVAILABLE, List.of(candidate), 1, 20, 1, false,
                Instant.now(), "https://www.law.go.kr"));
        when(vectors.publishedSource(candidate)).thenReturn(java.util.Optional.of("stored-source"));
        assertThat(collector.collect("질문")).hasSize(1);
        verify(vectors).reindexStoredLaw(any(), eq("stored-source"));
        verify(laws, never()).getLawDetail(any(), anyString());
    }

    @Test void incompletePageScanNeverIndexesPartialHighScoringResults() {
        when(rag.searchTop15(any(), anyString())).thenReturn(List.of());
        when(planner.decide(anyString(), anyList(), any(), anyList(), anyInt())).thenReturn(collect());
        var candidate = new LegalToolContracts.Candidate("123", "12", "관련 법령", "20260101", null, null, null);
        when(laws.searchLaws(any(), any())).thenReturn(new LegalToolContracts.SearchResult(
                LegalToolContracts.Status.AVAILABLE, List.of(candidate), 1, 20, 21, true,
                Instant.now(), "https://www.law.go.kr"))
                .thenThrow(new LegalToolException(LegalToolException.Reason.BUDGET_EXHAUSTED, false));
        assertThatThrownBy(() -> collector.collect("질문")).isInstanceOf(MolelawException.class);
        verify(laws, never()).getLawDetail(any(), anyString());
        verifyNoInteractions(vectors);
    }
    @Test void prematureDiscoveryStopIsReconsideredOnceWithoutFabricatingAnAnswer() {
        var plan = new QuestionRetrievalPlan(QuestionRetrievalPlan.Type.LAW_DISCOVERY,
                QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE, List.of("질문"), List.of(), List.of("위치", "실제 높이"));
        when(questions.plan("질문")).thenReturn(plan);
        when(rag.searchTop15(any(), anyString())).thenReturn(List.of(hit()));
        var insufficient = new EvidenceDecision(EvidenceDecision.Action.INSUFFICIENT, "위치 누락",
                List.of("위치"), List.of(), List.of(), List.of());
        when(planner.decide(eq("질문"), anyList(), eq(plan), anyList(), anyInt())).thenReturn(insufficient);
        when(planner.reconsiderDiscovery(eq("질문"), anyList(), eq(plan), anyList(), anyInt())).thenReturn(insufficient);
        assertThatThrownBy(() -> collector.collect("질문")).isInstanceOf(MolelawException.class);
        verify(planner, times(1)).reconsiderDiscovery(eq("질문"), anyList(), eq(plan), anyList(), anyInt());
        verifyNoInteractions(laws, vectors);
    }

    @Test void reconsideredDiscoveryCanReturnGroundedGeneralGuidance() {
        var plan = new QuestionRetrievalPlan(QuestionRetrievalPlan.Type.LAW_DISCOVERY,
                QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE, List.of("질문"), List.of(), List.of("위치"));
        when(questions.plan("질문")).thenReturn(plan);
        when(rag.searchTop15(any(), anyString())).thenReturn(List.of(hit()));
        when(planner.decide(eq("질문"), anyList(), eq(plan), anyList(), anyInt())).thenReturn(new EvidenceDecision(
                EvidenceDecision.Action.INSUFFICIENT, "위치 누락", List.of("위치"), List.of(), List.of(), List.of()));
        when(planner.reconsiderDiscovery(eq("질문"), anyList(), eq(plan), anyList(), anyInt()))
                .thenAnswer(call -> ready(call.getArgument(1)));
        assertThat(collector.collect("질문")).hasSize(1);
        verify(planner, times(1)).reconsiderDiscovery(eq("질문"), anyList(), eq(plan), anyList(), anyInt());
    }

    @Test void returnsOnlyServerAuthorizedReadyEvidenceWithProvenance() {
        var indexed = hit();
        when(rag.searchTop15(any(), eq("질문"))).thenReturn(List.of(indexed));
        when(planner.decide(eq("질문"), anyList(), any(), anyList(), anyInt())).thenAnswer(call -> ready(call.getArgument(1)));
        var evidence = collector.collect("질문");
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).kind()).isEqualTo("LAW_QDRANT");
        assertThat(evidence.get(0).provenance().sourceUuid()).isEqualTo(indexed.referenceId());
        verifyNoInteractions(laws, vectors);
    }
    @Test void emptyDatabaseCollectsAcrossAnEmptyFilteredPageThenQueriesAgain() {
        when(rag.searchTop15(any(), eq("질문"))).thenReturn(List.of(), List.of(hit()));
        when(planner.decide(eq("질문"), anyList(), any(), anyList(), anyInt())).thenAnswer(call -> {
            List<EvidenceItem> evidence = call.getArgument(1);
            return evidence.isEmpty() ? collect() : ready(evidence);
        });
        var candidate = new LegalToolContracts.Candidate("123", "001823", "민법", "20260101", null, null, null);
        when(laws.searchLaws(any(), any())).thenAnswer(call -> {
            LegalToolContracts.SearchRequest request = call.getArgument(1);
            assertThat(request.lawFilters().exactName()).isTrue();
            assertThat(request.lawFilters().status()).isEqualTo(LawSearchFilters.CURRENT);
            return new LegalToolContracts.SearchResult(request.page() == 1 ? LegalToolContracts.Status.EMPTY : LegalToolContracts.Status.AVAILABLE,
                    request.page() == 1 ? List.of() : List.of(candidate), request.page(), 20, 21,
                    request.page() == 1, Instant.now(), "https://www.law.go.kr/");
        });
        String source = UUID.randomUUID().toString();
        when(laws.getLawDetail(any(), eq("123"))).thenReturn(new LegalToolContracts.DetailResult(source, "123", "001823", "20260101",
                "민법", 1, true, Instant.now(), "https://www.law.go.kr/", List.of("1")));
        assertThat(collector.collect("질문")).hasSize(1);
        verify(laws, times(2)).searchLaws(any(), any());
        verify(vectors).vectorizeLaw(any(), eq(source));
        verify(rag, times(2)).searchTop15(any(), eq("질문"));
    }
    @Test void insufficientEvidenceStopsWithoutCollectingOrAnswering() {
        when(rag.searchTop15(any(), anyString())).thenReturn(List.of());
        when(planner.decide(anyString(), anyList(), any(), anyList(), anyInt())).thenReturn(new EvidenceDecision(EvidenceDecision.Action.INSUFFICIENT,
                "사실관계 부족", List.of("행위 내용"), List.of(), List.of(), List.of()));
        assertThatThrownBy(() -> collector.collect("질문")).isInstanceOfSatisfying(MolelawException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.EVIDENCE_INSUFFICIENT));
        verifyNoInteractions(laws, vectors);
    }

    @Test void firstCompletedLawReturnsToSearchAndDecisionBeforeIndexingTheNextCandidate() {
        when(rag.searchTop15(any(), anyString())).thenReturn(List.of(), List.of(hit()));
        when(planner.decide(anyString(), anyList(), any(), anyList(), anyInt())).thenAnswer(call ->
                ((List<?>) call.getArgument(1)).isEmpty() ? collect() : ready(call.getArgument(1)));
        var first = new LegalToolContracts.Candidate("123", "12", "첫 법령", "20260101", null, null, null);
        var second = new LegalToolContracts.Candidate("456", "34", "다음 법령", "20260101", null, null, null);
        when(laws.searchLaws(any(), any())).thenReturn(new LegalToolContracts.SearchResult(
                LegalToolContracts.Status.AVAILABLE, List.of(first, second), 1, 20, 2, false, Instant.now(), "https://www.law.go.kr"));
        String source = UUID.randomUUID().toString();
        when(laws.getLawDetail(any(), eq("123"))).thenReturn(new LegalToolContracts.DetailResult(
                source, "123", "12", "20260101", "첫 법령", 1, true, Instant.now(), "https://www.law.go.kr", List.of("1")));
        assertThat(collector.collect("질문")).hasSize(1);
        verify(laws, never()).getLawDetail(any(), eq("456"));
        var order = inOrder(rag, vectors, planner);
        order.verify(rag).searchTop15(any(), anyString());
        order.verify(planner).decide(anyString(), anyList(), any(), anyList(), eq(4));
        order.verify(vectors).vectorizeLaw(any(), eq(source));
        order.verify(rag).searchTop15(any(), anyString());
        order.verify(planner).decide(anyString(), anyList(), any(), anyList(), eq(3));
    }

    @Test void irrelevantFirstPageIsNotFetchedOrIndexedAndTheNextPageIsReviewed() {
        when(rag.searchTop15(any(), anyString())).thenReturn(List.of(), List.of(hit()));
        when(planner.decide(anyString(), anyList(), any(), anyList(), anyInt())).thenAnswer(call ->
                ((List<?>) call.getArgument(1)).isEmpty() ? collect() : ready(call.getArgument(1)));
        var unrelated = new LegalToolContracts.Candidate("999", "99", "무관한 행사 지원법", "20260101", null, null, null);
        var relevant = new LegalToolContracts.Candidate("123", "12", "관련 법령", "20260101", null, null, null);
        when(laws.searchLaws(any(), any())).thenAnswer(call -> {
            LegalToolContracts.SearchRequest request = call.getArgument(1);
            return new LegalToolContracts.SearchResult(LegalToolContracts.Status.AVAILABLE,
                    request.page() < 4 ? List.of(unrelated) : List.of(relevant), request.page(), 20, 61,
                    request.page() < 4, Instant.now(), "https://www.law.go.kr");
        });
        when(candidates.rank(anyString(), any(), any(), anyList())).thenAnswer(call -> {
            List<LegalToolContracts.Candidate> page = call.getArgument(3);
            return page.stream().map(c -> new com.MoleLaw_backend.law.dto.retrieval.LawCandidateSelection.Ranking(c.id(), c.id().equals("123") ? 95 : 80, "관련성")).toList();
        });
        var source = UUID.randomUUID().toString();
        when(laws.getLawDetail(any(), eq("123"))).thenReturn(new LegalToolContracts.DetailResult(
                source, "123", "12", "20260101", "관련 법령", 1, true, Instant.now(), "https://www.law.go.kr", List.of("1")));
        assertThat(collector.collect("질문")).hasSize(1);
        verify(laws, never()).getLawDetail(any(), eq("999"));
        verify(laws, times(4)).searchLaws(any(), any());
        var order = inOrder(laws);
        order.verify(laws, times(4)).searchLaws(any(), any());
        order.verify(laws).getLawDetail(any(), eq("123"));
        verify(vectors, times(1)).vectorizeLaw(any(), eq(source));
    }

    @Test void classifiedQueriesAndGeneralGuidanceDoNotRequireFactsForASpecificPermitDecision() {
        var plan = new QuestionRetrievalPlan(QuestionRetrievalPlan.Type.LAW_DISCOVERY,
                QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE,
                List.of("공항 주변 장애물 제한", "건축물 높이 제한"), List.of(), List.of("부지 위치", "실제 높이"));
        when(questions.plan("질문")).thenReturn(plan);
        var indexed = hit();
        when(rag.searchTop15(any(), anyString())).thenReturn(List.of(indexed));
        when(planner.decide(eq("질문"), anyList(), eq(plan), anyList(), anyInt())).thenAnswer(call -> ready(call.getArgument(1)));
        var result = collector.collectForAnswer("질문");
        assertThat(result.plan().responseScope()).isEqualTo(QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE);
        assertThat(result.plan().missingFacts()).containsExactly("부지 위치", "실제 높이");
        verify(rag).searchTop15(any(), eq("공항 주변 장애물 제한"));
        verify(rag).searchTop15(any(), eq("건축물 높이 제한"));
        verify(rag, never()).searchTop15(any(), eq("질문"));
        verifyNoInteractions(laws, vectors);
    }

    @Test void emptyLawSearchFeedsBackToPlannerAndAnAlternativeConditionCanSucceed() {
        when(rag.searchTop15(any(), eq("질문"))).thenReturn(List.of(), List.of(hit()));
        var oldCondition = new EvidenceDecision.LawSearch("옛 법령명", EvidenceDecision.SearchField.TITLE, true, null);
        var oldDecision = new EvidenceDecision(EvidenceDecision.Action.COLLECT_LAW, "원문 필요",
                List.of("근거"), List.of(), List.of(), List.of(oldCondition));
        var rounds = new java.util.concurrent.atomic.AtomicInteger();
        when(planner.decide(eq("질문"), anyList(), any(), anyList(), anyInt())).thenAnswer(call -> {
            int round = rounds.getAndIncrement();
            if (round == 0) return oldDecision;
            if (round == 1) {
                List<RetrievalAttempt> feedback = call.getArgument(3);
                assertThat(feedback).anySatisfy(attempt -> {
                    assertThat(attempt.conditions()).isEqualTo(oldCondition);
                    assertThat(attempt.outcome()).isEqualTo(RetrievalAttempt.Outcome.NO_RESULTS);
                });
                return collect();
            }
            return ready(call.getArgument(1));
        });
        when(laws.searchLaws(any(), any())).thenAnswer(call -> {
            LegalToolContracts.SearchRequest request = call.getArgument(1);
            if (request.query().equals("옛 법령명")) return new LegalToolContracts.SearchResult(
                    LegalToolContracts.Status.EMPTY, List.of(), 1, 20, 0, false, Instant.now(), "https://www.law.go.kr");
            return new LegalToolContracts.SearchResult(LegalToolContracts.Status.AVAILABLE,
                    List.of(new LegalToolContracts.Candidate("123", "001823", "민법", "20260101", null, null, null)),
                    1, 20, 1, false, Instant.now(), "https://www.law.go.kr");
        });
        when(laws.getLawDetail(any(), eq("123"))).thenReturn(new LegalToolContracts.DetailResult("source", "123",
                "001823", "20260101", "민법", 1, true, Instant.now(), "https://www.law.go.kr", List.of("1")));
        assertThat(collector.collect("질문")).hasSize(1);
        verify(laws, times(2)).searchLaws(any(), any());
        verify(vectors).vectorizeLaw(any(), eq("source"));
    }

    @Test void sameExhaustedLawConditionCannotBeFetchedAgain() {
        when(rag.searchTop15(any(), anyString())).thenReturn(List.of());
        when(planner.decide(anyString(), anyList(), any(), anyList(), anyInt())).thenReturn(collect());
        when(laws.searchLaws(any(), any())).thenReturn(new LegalToolContracts.SearchResult(
                LegalToolContracts.Status.EMPTY, List.of(), 1, 20, 0, false, Instant.now(), "https://www.law.go.kr"));
        assertThatThrownBy(() -> collector.collect("질문")).isInstanceOfSatisfying(MolelawException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.EVIDENCE_INSUFFICIENT));
        verify(laws, times(1)).searchLaws(any(), any());
        verify(planner, times(2)).decide(anyString(), anyList(), any(), anyList(), anyInt());
    }

    @Test void unchangedVectorResultsReachThePlannerAsFeedbackRatherThanImmediateFailure() {
        var indexed = hit();
        when(rag.searchTop15(any(), anyString())).thenReturn(List.of(indexed));
        var rounds = new java.util.concurrent.atomic.AtomicInteger();
        when(planner.decide(anyString(), anyList(), any(), anyList(), anyInt())).thenAnswer(call -> {
            if (rounds.getAndIncrement() == 0) return new EvidenceDecision(EvidenceDecision.Action.SEARCH_AGAIN,
                    "질의 변경", List.of("추가 근거"), List.of(), List.of("새 질의"), List.of());
            List<RetrievalAttempt> attempts = call.getArgument(3);
            assertThat(attempts.get(attempts.size() - 1).outcome()).isEqualTo(RetrievalAttempt.Outcome.NO_NEW_EVIDENCE);
            return new EvidenceDecision(EvidenceDecision.Action.INSUFFICIENT, "추가 자료 없음",
                    List.of("근거"), List.of(), List.of(), List.of());
        });
        assertThatThrownBy(() -> collector.collect("질문")).isInstanceOf(MolelawException.class);
        verify(planner, times(2)).decide(anyString(), anyList(), any(), anyList(), anyInt());
    }

    @Test void duplicateQueryIsSkippedAndReportedToTheNextDecision() {
        var indexed = hit();
        when(rag.searchTop15(any(), anyString())).thenReturn(List.of(indexed));
        var rounds = new java.util.concurrent.atomic.AtomicInteger();
        when(planner.decide(anyString(), anyList(), any(), anyList(), anyInt())).thenAnswer(call -> {
            if (rounds.getAndIncrement() == 0) return new EvidenceDecision(EvidenceDecision.Action.SEARCH_AGAIN,
                    "재검색", List.of("추가 근거"), List.of(), List.of("질문"), List.of());
            List<RetrievalAttempt> attempts = call.getArgument(3);
            assertThat(attempts.get(attempts.size() - 1).outcome()).isEqualTo(RetrievalAttempt.Outcome.DUPLICATE);
            assertThat((int) call.getArgument(4)).isEqualTo(3);
            return ready(call.getArgument(1));
        });
        assertThat(collector.collect("질문")).hasSize(1);
        verify(rag, times(1)).searchTop15(any(), eq("질문"));
        verify(planner, times(2)).decide(anyString(), anyList(), any(), anyList(), anyInt());
    }

    @Test void repeatedDuplicateChoicesStillStopAtTheExistingActionLimit() {
        when(rag.searchTop15(any(), anyString())).thenReturn(List.of());
        when(planner.decide(anyString(), anyList(), any(), anyList(), anyInt())).thenReturn(
                new EvidenceDecision(EvidenceDecision.Action.SEARCH_AGAIN, "재검색", List.of("근거"),
                        List.of(), List.of("질문"), List.of()));
        assertThatThrownBy(() -> collector.collect("질문")).isInstanceOf(MolelawException.class);
        verify(rag, times(1)).searchTop15(any(), eq("질문"));
        verify(planner, times(5)).decide(anyString(), anyList(), any(), anyList(), anyInt());
        verifyNoInteractions(laws, vectors);
    }

    @Test void apiFailureDoesNotBecomeEmptySearchFeedbackOrTriggerAProviderRetry() {
        when(rag.searchTop15(any(), anyString())).thenReturn(List.of());
        when(planner.decide(anyString(), anyList(), any(), anyList(), anyInt())).thenReturn(collect());
        when(laws.searchLaws(any(), any())).thenThrow(new LegalToolException(LegalToolException.Reason.API_FAILURE, true));
        assertThatThrownBy(() -> collector.collect("질문")).isInstanceOfSatisfying(MolelawException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.RETRIEVAL_UNAVAILABLE));
        verify(planner, times(1)).decide(anyString(), anyList(), any(), anyList(), anyInt());
        verifyNoInteractions(vectors);
    }

    @Test void outOfScopeClassificationStopsBeforeEmbeddingOrTools() {
        when(questions.plan("질문")).thenReturn(new QuestionRetrievalPlan(QuestionRetrievalPlan.Type.OUT_OF_SCOPE,
                QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE, List.of(), List.of(), List.of()));
        assertThatThrownBy(() -> collector.collect("질문")).isInstanceOf(MolelawException.class);
        verifyNoInteractions(rag, laws, vectors, planner);
    }
    @Test void rejectsModelInventedEvidenceIds() {
        when(rag.searchTop15(any(), anyString())).thenReturn(List.of(hit()));
        when(planner.decide(anyString(), anyList(), any(), anyList(), anyInt())).thenReturn(new EvidenceDecision(EvidenceDecision.Action.ANSWER_READY,
                "완료", List.of(), List.of(UUID.randomUUID().toString()), List.of(), List.of()));
        assertThatThrownBy(() -> collector.collect("질문")).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(laws, vectors);
    }
    @Test void embeddingOverloadIsReportedWithoutRetryStorm() {
        when(rag.searchTop15(any(), anyString())).thenThrow(new LegalToolException(LegalToolException.Reason.BUSY, true));
        assertThatThrownBy(() -> collector.collect("질문")).isInstanceOfSatisfying(MolelawException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.RETRIEVAL_BUSY));
        verify(rag).searchTop15(any(), anyString());
        verifyNoInteractions(planner, laws, vectors);
    }
    @Test void unavailableVectorConfigurationDoesNotFallBackToLegacyTables() {
        var disabled = new LegalEvidenceCollector(provider(null), provider(null), laws, planner, questions, candidates, mock(AiSearchEvidenceCollector.class), com.MoleLaw_backend.law.dto.retrieval.RetrievalBackend.QDRANT, new com.MoleLaw_backend.law.config.LawCandidateSelectionProperties());
        assertThatThrownBy(() -> disabled.collect("질문")).isInstanceOfSatisfying(MolelawException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.RETRIEVAL_UNAVAILABLE));
        verifyNoInteractions(planner, laws);
    }
}
