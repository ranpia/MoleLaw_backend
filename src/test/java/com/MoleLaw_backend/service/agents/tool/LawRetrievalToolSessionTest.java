package com.MoleLaw_backend.service.agents.tool;

import com.MoleLaw_backend.config.LawCandidateSelectionProperties;
import com.MoleLaw_backend.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.dto.retrieval.*;
import com.MoleLaw_backend.dto.tool.LegalToolContracts.*;
import com.MoleLaw_backend.dto.vector.LawSearchEvidence;
import com.MoleLaw_backend.service.agents.retrieval.LawCandidateSelector;
import com.MoleLaw_backend.util.exception.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LawRetrievalToolSessionTest {
    private final LawRagSearchToolService rag = mock(LawRagSearchToolService.class);
    private final LawVectorizationToolService vector = mock(LawVectorizationToolService.class);
    private final LegalApiToolService laws = mock(LegalApiToolService.class);
    private final LawCandidateSelector selector = mock(LawCandidateSelector.class);
    private final EvidenceReadToolService reader = mock(EvidenceReadToolService.class);
    private final List<List<EvidenceItem>> published = new ArrayList<>();
    private final List<String> stages = new ArrayList<>();
    private final RetrievalObserver observer = new RetrievalObserver() {
        @Override public void progress(String stage) { stages.add(stage); }
        @Override public void evidence(List<EvidenceItem> evidence) { published.add(evidence); }
    };
    private final QuestionRetrievalPlan plan = new QuestionRetrievalPlan(QuestionRetrievalPlan.Type.LAW_EXPLANATION,
            QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE, List.of("질문"), List.of(), List.of());

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        when(rag.searchTop15(any(), anyString())).thenReturn(List.of(hit("123", "1", "검증할 조문 본문")));
        when(selector.rank(anyString(), any(), any(), anyList())).thenAnswer(call ->
                ((List<Candidate>)call.getArgument(3)).stream().map(c -> new LawCandidateSelection.Ranking(c.id(), 95, "관련")).toList());
        when(reader.readEvidence(anyList(), anyList())).thenAnswer(call -> {
            List<EvidenceItem> evidence = call.getArgument(0); List<String> ids = call.getArgument(1);
            return evidence.stream().filter(item -> ids.contains(item.id())).toList();
        });
        when(vector.publishedSource(any())).thenReturn(Optional.empty());
    }
    private LawRetrievalToolSession session(boolean fixed, int bodyLimit) {
        var clock = Clock.systemUTC();
        return new LawRetrievalToolSession("질문", plan, new LegalToolContext(LegalToolContext.Purpose.FIRST_QUESTION,
                "trace", clock.instant().plus(Duration.ofMinutes(3)), 128, clock), rag, vector, laws,
                selector, reader, new LawCandidateSelectionProperties(), observer, fixed, bodyLimit, new ObjectMapper());
    }
    private LawRetrievalToolSession session() { return session(false, 12000); }
    public static LawSearchEvidence hit(String mst, String unit, String body) {
        return new LawSearchEvidence(mst + ":" + unit, UUID.nameUUIDFromBytes(("source"+mst).getBytes()).toString(), mst,
                "001823", "20260101", "법령"+mst+" 제"+unit+"조", body, body, "https://www.law.go.kr/lsInfoP.do?lsiSeq="+mst,
                .9, "revision", "a".repeat(64), UUID.nameUUIDFromBytes(("generation"+mst).getBytes()).toString());
    }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> catalog(Map<String,Object> result) {
        return (List<Map<String,Object>>)result.get("candidates");
    }
    private String firstId(LawRetrievalToolSession session) {
        return (String)catalog(session.searchArticles(List.of("질문"))).get(0).get("id");
    }
    private Candidate candidate(String id) { return new Candidate(id,"001823","민법","20260101",null,null,null); }
    private SearchResult page(int page, boolean next, List<Candidate> rows) {
        return new SearchResult(rows.isEmpty()?Status.EMPTY:Status.AVAILABLE, rows,page,20,next?21:rows.size(),next,
                Instant.now(),"https://www.law.go.kr");
    }
    private void approve(LawRetrievalToolSession session) {
        when(laws.searchLaws(any(),any())).thenReturn(page(1,false,List.of(candidate("123"))));
        session.searchLaws("민법", EvidenceDecision.SearchField.TITLE,true,null);
    }
    private EvidenceDecision ready(String id) {
        return new EvidenceDecision(EvidenceDecision.Action.ANSWER_READY,"충분",List.of(),List.of(id),List.of(),List.of());
    }

    @Test void searchOnlyReturnsMetadataAndUnreadCandidatesCannotAuthorizeAnAnswer() {
        var session=session(); String id=firstId(session);
        assertThat(published).isEmpty(); verifyNoInteractions(reader);
        assertThatThrownBy(() -> session.finish(ready(id))).isInstanceOf(EvidenceDecisionValidationException.class);
    }
    @Test void bodyReadsPublishConfirmedEvidenceAndOnlyReadIdsCanFinish() {
        var session=session(); String id=firstId(session);
        var read=session.readArticles(List.of(id));
        assertThat(read.get("status")).isEqualTo("READ");
        assertThat(published.get(0)).extracting(EvidenceItem::id).containsExactly(id);
        assertThat(session.finish(ready(id)).evidence()).extracting(EvidenceItem::id).containsExactly(id);
    }
    @Test void inventedReadIdsAreRejectedBeforeSourceRestoration() {
        var session=session(); firstId(session);
        assertThatThrownBy(() -> session.readArticles(List.of("invented"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(reader); assertThat(published).isEmpty();
    }
    @Test void partialAnswerPreservesUnresolvedIssuesAndDowngradesToGeneralGuidance() {
        var session=session(); String id=firstId(session);session.readArticles(List.of(id));
        var result=session.finish(new EvidenceDecision(EvidenceDecision.Action.ANSWER_PARTIAL,"일부 근거",List.of("적용 시점"),List.of(id),List.of(),List.of()));
        assertThat(result.plan().responseScope()).isEqualTo(QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE);
        assertThat(result.unresolvedIssues()).containsExactly("적용 시점");
    }
    @Test void duplicateAndPunctuationOnlyQueriesDoNotRepeatEmbedding() {
        var session=session();session.searchArticles(List.of("민법 제1조"));
        assertThat(session.searchArticles(List.of("민법 제1조! ")).get("status")).isEqualTo("DUPLICATE");
        verify(rag,times(1)).searchTop15(any(),anyString());
    }
    @Test void keywordCountIsNotLimitedToThreeOrSixAndNaturalLanguageCanBeUsed() {
        var session=session();var queries=IntStream.range(0,10).mapToObj(i -> "사건 사실관계와 질의 " + i).toList();
        session.searchArticles(queries);
        verify(rag,times(10)).searchTop15(any(),anyString());
    }
    @Test void queryBatchPreservesResultsFromDifferentIssues() {
        when(rag.searchTop15(any(),anyString())).thenAnswer(call -> List.of(hit(
                call.<String>getArgument(1).endsWith("1")?"123":"456","1","본문")));
        var result=session().searchArticles(List.of("쟁점1","쟁점2"));
        assertThat(catalog(result)).extracting(row -> row.get("sourceId")).contains("123:1","456:1");
    }
    @Test void approvedCoverageSurvivesLaterRankings() {
        var session=session();String id=firstId(session);session.readArticles(List.of(id));
        when(rag.searchTop15(any(),eq("새 쟁점"))).thenReturn(IntStream.range(200,215).mapToObj(i -> hit(""+i,"1","다른 본문")).toList());
        var result=session.searchArticles(List.of("새 쟁점"));
        assertThat(catalog(result)).extracting(row -> row.get("id")).contains(id);
        assertThat(session.finish(ready(id)).evidence()).hasSize(1);
    }
    @Test void changedGenerationInvalidatesPreviouslyReadIdsInsteadOfMixingSources() {
        var session=session();String id=firstId(session);session.readArticles(List.of(id));
        var old=hit("123","1","본문");
        var next=new LawSearchEvidence(old.unitId(),UUID.randomUUID().toString(),old.externalId(),old.lawId(),old.effectiveDate(),
                old.title(),"새 본문","새 본문",old.sourceUrl(),.9,old.modelRevision(),old.configFingerprint(),UUID.randomUUID().toString());
        when(rag.searchTop15(any(),eq("다른 표현"))).thenReturn(List.of(next));
        session.searchArticles(List.of("다른 표현"));
        assertThatThrownBy(() -> session.finish(ready(id))).isInstanceOf(EvidenceDecisionValidationException.class);
    }
    @Test void oversizedBodiesAreNotSilentlyTruncatedOrAuthorized() {
        when(rag.searchTop15(any(),anyString())).thenReturn(List.of(hit("123","1","긴 본문 ".repeat(500))));
        var session=session(false,128);String id=firstId(session);
        assertThat(session.readArticles(List.of(id)).get("status")).isEqualTo("NO_BODY");
        assertThat(session.readIds()).isEmpty();
    }
    @Test void emptySearchReturnsFeedbackSoAnotherQueryCanBeSelected() {
        when(rag.searchTop15(any(),eq("첫 조건"))).thenReturn(List.of());
        var session=session();assertThat(session.searchArticles(List.of("첫 조건")).get("status")).isEqualTo("EMPTY");
        assertThat(session.searchArticles(List.of("다른 조건")).get("status")).isEqualTo("AVAILABLE");
    }
    @Test void metadataSearchScansPastFilteredEmptyPagesAndDoesNotCollectAutomatically() {
        when(laws.searchLaws(any(),any())).thenAnswer(call -> {
            SearchRequest request=call.getArgument(1);
            assertThat(request.lawFilters().exactName()).isTrue();
            return request.page()==1?page(1,true,List.of()):page(2,false,List.of(candidate("123")));
        });
        var result=session().searchLaws("민법",EvidenceDecision.SearchField.TITLE,true,null);
        assertThat(result.get("pages")).isEqualTo(2);assertThat(result.get("searchComplete")).isEqualTo(true);
        verify(laws,times(2)).searchLaws(any(),any());verifyNoInteractions(vector);
        verify(laws,never()).getLawDetail(any(),anyString());
    }
    @Test void metadataThresholdExcludesIrrelevantCandidatesBeforeCollection() {
        when(laws.searchLaws(any(),any())).thenReturn(page(1,false,List.of(candidate("123"))));
        when(selector.rank(anyString(),any(),any(),anyList())).thenReturn(List.of(new LawCandidateSelection.Ranking("123",4,"무관련")));
        var session=session();assertThat(session.searchLaws("민법",EvidenceDecision.SearchField.TITLE,true,null).get("status")).isEqualTo("EMPTY");
        assertThatThrownBy(() -> session.collectLaw("123")).isInstanceOf(IllegalArgumentException.class);
        verify(laws,never()).getLawDetail(any(),anyString());
    }
    @Test void incompletePageScanCannotAuthorizePartialCandidateCollection() {
        when(laws.searchLaws(any(),any())).thenReturn(page(1,true,List.of(candidate("123"))))
                .thenThrow(new LegalToolException(LegalToolException.Reason.BUDGET_EXHAUSTED,false));
        var session=session();assertThatThrownBy(() -> session.searchLaws("민법",EvidenceDecision.SearchField.TITLE,true,null)).isInstanceOf(LegalToolException.class);
        assertThatThrownBy(() -> session.collectLaw("123")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(vector);
    }
    @Test void repeatedLawSearchDoesNotRefetchAnExhaustedCondition() {
        when(laws.searchLaws(any(),any())).thenReturn(page(1,false,List.of()));
        var session=session();session.searchLaws("민법",EvidenceDecision.SearchField.TITLE,true,null);
        assertThat(session.searchLaws("민법",EvidenceDecision.SearchField.TITLE,true,null).get("status")).isEqualTo("DUPLICATE");
        verify(laws,times(1)).searchLaws(any(),any());
    }
    @Test void alreadyObservedLawIsExcludedBeforeMetadataReranking() {
        var session=session();firstId(session);approve(session);
        var captor=org.mockito.ArgumentCaptor.forClass(List.class);
        verify(selector).rank(anyString(),any(),any(),captor.capture());assertThat(captor.getValue()).isEmpty();
    }
    @Test void collectionRequiresAnApprovedCandidateAndNeverAcceptsArbitrarySourceIds() {
        var session=session();assertThatThrownBy(() -> session.collectLaw("123")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(laws,vector);
    }
    @Test void collectionIndexesOnceThenLetsTheModelExplicitlyRequery() {
        when(rag.searchTop15(any(),anyString())).thenReturn(List.of(),List.of(hit("123","1","새 본문")));
        when(laws.getLawDetail(any(),eq("123"))).thenReturn(new DetailResult("source","123","001823","20260101","민법",1,true,
                Instant.now(),"https://www.law.go.kr",List.of("1")));
        var session=session();session.searchArticles(List.of("질문"));approve(session);
        assertThat(session.collectLaw("123").get("status")).isEqualTo("INDEXED");
        assertThat(session.collectLaw("123").get("status")).isEqualTo("DUPLICATE");
        verify(rag,times(1)).searchTop15(any(),anyString());verify(vector,times(1)).vectorizeLaw(any(),eq("source"));
        assertThat(session.searchArticles(List.of("질문")).get("status")).isEqualTo("AVAILABLE");
    }
    @Test void publishedSourceIsReusedWithoutAnotherDetailOrNewEmbeddingRequest() {
        var session=session();approve(session);when(vector.publishedSource(any())).thenReturn(Optional.of("stored-source"));
        session.collectLaw("123");verify(vector).reindexStoredLaw(any(),eq("stored-source"));
        verify(laws,never()).getLawDetail(any(),anyString());verify(vector,never()).vectorizeLaw(any(),anyString());
    }
    @Test void fixedIndexOnlyRejectsExternalSearchAndCollection() {
        var session=session(true,12000);
        assertThatThrownBy(() -> session.searchLaws("민법",EvidenceDecision.SearchField.TITLE,true,null)).isInstanceOf(MolelawException.class);
        assertThatThrownBy(() -> session.collectLaw("123")).isInstanceOf(MolelawException.class);
        verifyNoInteractions(laws,vector);
    }
    @Test void providerFailureIsNotConvertedToAnEmptySearchOrRetried() {
        when(laws.searchLaws(any(),any())).thenThrow(new LegalToolException(LegalToolException.Reason.API_FAILURE,true));
        assertThatThrownBy(() -> session().searchLaws("민법",EvidenceDecision.SearchField.TITLE,true,null)).isInstanceOf(LegalToolException.class);
        verify(laws,times(1)).searchLaws(any(),any());
    }
    @Test void requestToolLimitStopsDuplicateLoopWithoutAdditionalApiCalls() {
        var session=session();session.searchArticles(List.of("질문"));
        for(int i=1;i<32;i++) session.searchArticles(List.of("질문"));
        assertThatThrownBy(() -> session.searchArticles(List.of("질문"))).isInstanceOf(MolelawException.class);
        verify(rag,times(1)).searchTop15(any(),anyString());
    }
}
