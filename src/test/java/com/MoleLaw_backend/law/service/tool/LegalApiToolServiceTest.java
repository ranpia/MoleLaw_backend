package com.MoleLaw_backend.law.service.tool;

import com.MoleLaw_backend.law.entity.LegalSource;
import com.MoleLaw_backend.law.dto.request.LawSearchFilters;
import com.MoleLaw_backend.law.service.LawSearchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static com.MoleLaw_backend.law.service.tool.LegalToolContracts.*;
import static com.MoleLaw_backend.law.service.tool.LegalToolException.Reason.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegalApiToolServiceTest {
    private final LawSearchService laws = mock(LawSearchService.class);
    private final OpenLawCaseClient cases = mock(OpenLawCaseClient.class);
    private final LegalSourceService sources = mock(LegalSourceService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock();
    private final LegalApiToolService tools = new LegalApiToolService(laws, cases, sources, mapper);

    @BeforeEach
    void saveReturnsImmutableReference() {
        lenient().when(sources.save(anyString(), anyString(), nullable(String.class), nullable(String.class),
                anyString(), any(JsonNode.class))).thenAnswer(call -> new LegalSource(call.getArgument(0),
                call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4),
                "hash", call.getArgument(5).toString()));
    }

    @Test
    void lawPagesKeepMetadataAndDuplicateRequestsDoNotFetchAgain() {
        when(laws.searchPage("민법", 1, null, 1, 1)).thenReturn(lawPage("11", 1, 2));
        when(laws.searchPage("민법", 1, null, 2, 1)).thenReturn(lawPage("12", 2, 2));
        var context = context(4);
        var first = tools.searchLaws(context, request(1));
        assertThat(first.status()).isEqualTo(Status.AVAILABLE);
        assertThat(first.hasNext()).isTrue();
        assertThat(first.totalCount()).isEqualTo(2);
        assertThat(first.candidates().get(0).lawId()).isEqualTo("100");
        assertThat(first.candidates().get(0).effectiveDate()).isEqualTo("20260101");
        assertThat(tools.searchLaws(context, request(1)).status()).isEqualTo(Status.DUPLICATE);
        assertThat(tools.searchLaws(context, request(2)).hasNext()).isFalse();
        verify(laws, times(1)).searchPage("민법", 1, null, 1, 1);
        assertThat(context.callsUsed()).isEqualTo(2);
    }

    @Test
    void repeatedCandidatesStopFurtherExplorationOfSameCondition() {
        when(laws.searchPage("민법", 1, null, 1, 1)).thenReturn(lawPage("11", 1, 5));
        when(laws.searchPage("민법", 1, null, 2, 1)).thenReturn(lawPage("11", 2, 5));
        var context = context(5);
        tools.searchLaws(context, request(1));
        assertThat(tools.searchLaws(context, request(2)).status()).isEqualTo(Status.EXHAUSTED);
        assertThat(tools.searchLaws(context, request(3)).status()).isEqualTo(Status.EXHAUSTED);
        verify(laws, never()).searchPage("민법", 1, null, 3, 1);
    }

    @Test
    void emptyResultsAreDifferentFromMalformedResponses() throws Exception {
        when(cases.search("민법", 1, 1, 1)).thenReturn(mapper.readTree("{\"totalCnt\":0}"));
        assertThat(tools.searchCases(context(2), request(1)).status()).isEqualTo(Status.EMPTY);
        when(cases.search("민법", 1, 1, 1)).thenReturn(mapper.readTree("{\"totalCnt\":2}"));
        expectReason(() -> tools.searchCases(context(2), request(1)), INVALID_RESPONSE);
        when(laws.searchPage("민법", 1, null, 1, 1)).thenReturn("{}");
        expectReason(() -> tools.searchLaws(context(2), request(1)), INVALID_RESPONSE);
    }

    @Test
    void callBudgetPreventsAdditionalSearchAndDetailFetches() {
        when(laws.searchPage("민법", 1, null, 1, 1)).thenReturn(lawPage("11", 1, 2));
        var context = context(1);
        tools.searchLaws(context, request(1));
        assertThat(tools.searchLaws(context, request(2)).status()).isEqualTo(Status.BUDGET_EXHAUSTED);
        expectReason(() -> tools.getLawDetail(context, "11"), BUDGET_EXHAUSTED);
        verify(laws, never()).getLawDetailByMst(anyString());
    }

    @Test
    void deadlineCrossedDuringFetchDiscardsResponseBeforeSaving() {
        when(laws.searchPage("민법", 1, null, 1, 1)).thenReturn(lawPage("11", 1, 1));
        var context = context(3);
        tools.searchLaws(context, request(1));
        when(laws.getLawDetailByMst("11")).thenAnswer(call -> {
            clock.instant = clock.instant.plusSeconds(61);
            return lawDetail("100", "20260101");
        });
        expectReason(() -> tools.getLawDetail(context, "11"), BUDGET_EXHAUSTED);
        verifyNoInteractions(sources);
    }

    @Test
    void followUpBlocksAllFourToolsWithoutCallingDependencies() {
        var context = new LegalToolContext(LegalToolContext.Purpose.FOLLOW_UP, "follow-up",
                clock.instant().plusSeconds(60), 4, clock);
        expectReason(() -> tools.searchLaws(context, request(1)), FORBIDDEN);
        expectReason(() -> tools.searchCases(context, request(1)), FORBIDDEN);
        expectReason(() -> tools.getLawDetail(context, "11"), FORBIDDEN);
        expectReason(() -> tools.getCaseDetail(context, "22"), FORBIDDEN);
        verifyNoInteractions(laws, cases, sources);
    }

    @Test
    void unknownDetailIdsRequirePriorSearchInSameExecution() {
        expectReason(() -> tools.getLawDetail(context(3), "11"), UNKNOWN_ID);
        expectReason(() -> tools.getCaseDetail(context(3), "22"), UNKNOWN_ID);
        verifyNoInteractions(laws, cases, sources);
    }

    @Test
    void lawIdentityAndEffectiveDateMismatchesAreRejectedBeforePersistence() throws Exception {
        when(laws.searchPage("민법", 1, null, 1, 1)).thenReturn(lawPage("11", 1, 1));
        for (JsonNode detail : new JsonNode[]{lawDetail("999", "20260101"), lawDetail("100", "20270101")}) {
            var context = context(3);
            tools.searchLaws(context, request(1));
            when(laws.getLawDetailByMst("11")).thenReturn(detail);
            expectReason(() -> tools.getLawDetail(context, "11"), INVALID_RESPONSE);
        }
        verifyNoInteractions(sources);
    }

    @Test
    void lawDetailIsSavedOnceAndCachedReferenceIsReused() throws Exception {
        when(laws.searchPage("민법", 1, null, 1, 1)).thenReturn(lawPage("11", 1, 1));
        when(laws.getLawDetailByMst("11")).thenReturn(lawDetail("100", "20260101"));
        var context = context(3);
        tools.searchLaws(context, request(1));
        var detail = tools.getLawDetail(context, "11");
        assertThat(detail.referenceId()).isNotBlank();
        assertThat(detail.articleCount()).isEqualTo(1);
        assertThat(detail.contentAvailable()).isTrue();
        assertThat(tools.getLawDetail(context, "11")).isSameAs(detail);
        verify(laws, times(1)).getLawDetailByMst("11");
        verify(sources, times(1)).save(eq("LAW"), eq("11"), eq("100"), eq("20260101"), anyString(), any());
        assertThat(context.callsUsed()).isEqualTo(2);
    }

    @Test
    void caseSearchAndDetailReturnMetadataAndStoredReference() throws Exception {
        when(cases.search("민법", 1, 1, 1)).thenReturn(mapper.readTree("""
                {"totalCnt":1,"page":1,"prec":{"판례일련번호":"22","사건명":"손해배상",
                 "사건번호":"2025다1","선고일자":"20260101","법원명":"대법원"}}
                """));
        when(cases.detail("22")).thenReturn(mapper.readTree("""
                {"판례정보일련번호":"22","사건명":"손해배상","판례내용":"상세 원문"}
                """));
        var context = context(3);
        var search = tools.searchCases(context, request(1));
        assertThat(search.candidates().get(0).caseNumber()).isEqualTo("2025다1");
        assertThat(search.candidates().get(0).courtName()).isEqualTo("대법원");
        var detail = tools.getCaseDetail(context, "22");
        assertThat(detail.contentAvailable()).isTrue();
        assertThat(detail.referenceId()).isNotBlank();
        assertThat(detail.sourceUrl()).contains("precSeq=22");
        assertThat(tools.getCaseDetail(context, "22")).isSameAs(detail);
        verify(cases, times(1)).detail("22");
        verify(sources).save(eq("CASE"), eq("22"), isNull(), isNull(), anyString(), any());
    }

    @Test
    void invalidFiltersAndPaginationNeverReachExternalApis() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SearchRequest("민법", 3, null, 1, 20));
        assertThatIllegalArgumentException().isThrownBy(() -> new SearchRequest("민법", 1, "http://evil", 1, 20));
        assertThatIllegalArgumentException().isThrownBy(() -> new SearchRequest("민법", 1, null, 0, 20));
        assertThatIllegalArgumentException().isThrownBy(() -> new SearchRequest("민법", 1, null, 1, 101));
        assertThatIllegalArgumentException().isThrownBy(() -> tools.searchCases(context(3),
                new SearchRequest("민법", 1, "100", 1, 20)));
        verifyNoInteractions(laws, cases, sources);
    }

    @Test
    void apiFailuresRetainRetryPolicyAndConsumeBudget() {
        when(cases.search("민법", 1, 1, 1)).thenThrow(new OpenLawRequestException(false));
        var context = context(1);
        assertThatThrownBy(() -> tools.searchCases(context, request(1)))
                .isInstanceOfSatisfying(LegalToolException.class, error -> {
                    assertThat(error.getReason()).isEqualTo(API_FAILURE);
                    assertThat(error.isRetryable()).isFalse();
                });
        assertThat(tools.searchCases(context, request(1)).status()).isEqualTo(Status.BUDGET_EXHAUSTED);
    }

    @Test
    void finalPageBlocksLaterPagesButAllowsEarlierUnvisitedPages() {
        when(laws.searchPage("민법", 1, null, 2, 1)).thenReturn(lawPage("12", 2, 2));
        when(laws.searchPage("민법", 1, null, 1, 1)).thenReturn(lawPage("11", 1, 2));
        var context = context(5);
        tools.searchLaws(context, request(2));
        assertThat(tools.searchLaws(context, request(3)).status()).isEqualTo(Status.EXHAUSTED);
        assertThat(tools.searchLaws(context, request(1)).status()).isEqualTo(Status.AVAILABLE);
        verify(laws, never()).searchPage("민법", 1, null, 3, 1);
    }

    @Test
    void exactNameSkipsSimilarTitlesWithoutLosingNextPageOrAuthorizingTheirIds() {
        var filters = new LawSearchFilters(null, null, null, true);
        when(laws.searchPage("민법", 1, null, 1, 1, filters))
                .thenReturn(lawPage("11", 1, 2).replace("민법", "난민법"));
        when(laws.searchPage("민법", 1, null, 2, 1, filters)).thenReturn(lawPage("12", 2, 2));
        var context = context(5);
        var first = tools.searchLaws(context, new SearchRequest("민법", 1, null, 1, 1, filters));
        assertThat(first.status()).isEqualTo(Status.EMPTY);
        assertThat(first.candidates()).isEmpty();
        assertThat(first.hasNext()).isTrue();
        expectReason(() -> tools.getLawDetail(context, "11"), UNKNOWN_ID);
        var second = tools.searchLaws(context, new SearchRequest("민법", 1, null, 2, 1, filters));
        assertThat(second.status()).isEqualTo(Status.AVAILABLE);
        assertThat(second.candidates()).extracting(Candidate::title).containsExactly("민법");
        verify(laws, never()).getLawDetailByMst("11");
    }

    @Test
    void filterConditionsHaveSeparateDuplicateAndExhaustionState() {
        var current = new LawSearchFilters(null, "A0002", 3, false);
        var former = new LawSearchFilters(null, "A0002", 1, false);
        when(laws.searchPage("민법", 1, null, 1, 1, current)).thenReturn(lawPage("11", 1, 1));
        when(laws.searchPage("민법", 1, null, 1, 1, former)).thenReturn(lawPage("12", 1, 1));
        var context = context(5);
        var first = new SearchRequest("민법", 1, null, 1, 1, current);
        var second = new SearchRequest("민법", 1, null, 1, 1, former);
        assertThat(tools.searchLaws(context, first).status()).isEqualTo(Status.AVAILABLE);
        assertThat(tools.searchLaws(context, first).status()).isEqualTo(Status.DUPLICATE);
        assertThat(tools.searchLaws(context, second).candidates()).extracting(Candidate::id).containsExactly("12");
        assertThat(context.callsUsed()).isEqualTo(2);
    }

    @Test
    void casesRejectEveryLawOnlyFilterWithoutApiCalls() {
        for (var filters : new LawSearchFilters[]{new LawSearchFilters("100", null, null, false),
                new LawSearchFilters(null, "A0002", null, false),
                new LawSearchFilters(null, null, 2, false), new LawSearchFilters(null, null, null, true)}) {
            assertThatThrownBy(() -> tools.searchCases(context(3),
                    new SearchRequest("민법", 1, null, 1, 1, filters)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(laws, cases, sources);
    }

    @Test
    void sortingAndDateRangesDefineSeparateSearchConditions() {
        var ascending = new LawSearchFilters(null, null, null, true, "lasc", null, null);
        var dated = new LawSearchFilters(null, null, null, true, "efdes", "20200101~20261231", null);
        when(laws.searchPage("민법", 1, null, 1, 1, ascending)).thenReturn(lawPage("11", 1, 1));
        when(laws.searchPage("민법", 1, null, 1, 1, dated)).thenReturn(lawPage("12", 1, 1));
        var context = context(5);
        assertThat(tools.searchLaws(context, new SearchRequest("민법", 1, null, 1, 1, ascending)).status())
                .isEqualTo(Status.AVAILABLE);
        assertThat(tools.searchLaws(context, new SearchRequest("민법", 1, null, 1, 1, dated)).status())
                .isEqualTo(Status.AVAILABLE);
        assertThat(context.callsUsed()).isEqualTo(2);
        assertThatThrownBy(() -> tools.searchCases(context,
                new SearchRequest("민법", 1, null, 1, 1, dated))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void filterValidationRejectsInvalidIdsKindsStatusesAndExactBodySearch() {
        assertThatThrownBy(() -> new LawSearchFilters("1&OC=other", null, null, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LawSearchFilters("", null, null, false))
                .isInstanceOf(IllegalArgumentException.class);
        for (String kind : new String[]{"12345", "1234567", "ABCDEF"}) {
            assertThatThrownBy(() -> new LawSearchFilters(null, kind, null, false))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (int status : new int[]{0, 4}) {
            assertThatThrownBy(() -> new LawSearchFilters(null, null, status, false))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new SearchRequest("민법", 2, null, 1, 1,
                new LawSearchFilters(null, null, null, true))).isInstanceOf(IllegalArgumentException.class);
        assertThat(request(1).lawFilters()).isEqualTo(LawSearchFilters.NONE);
        assertThat(new SearchRequest("민법", 1, null, 1, 1, null).lawFilters()).isEqualTo(LawSearchFilters.NONE);
    }

    @Test
    void lawIdFilterAcceptsNumericEquivalentLeadingZerosAndRejectsWrongResponseId() {
        var filters = new LawSearchFilters("000100", null, null, false);
        when(laws.searchPage("민법", 1, null, 1, 1, filters)).thenReturn(lawPage("11", 1, 1));
        var request = new SearchRequest("민법", 1, null, 1, 1, filters);
        assertThat(tools.searchLaws(context(3), request).candidates()).extracting(Candidate::lawId)
                .containsExactly("100");
        when(laws.searchPage("민법", 1, null, 1, 1, filters))
                .thenReturn(lawPage("11", 1, 1).replace("\"법령ID\":\"100\"", "\"법령ID\":\"101\""));
        var rejected = context(3);
        expectReason(() -> tools.searchLaws(rejected, request), INVALID_RESPONSE);
        expectReason(() -> tools.getLawDetail(rejected, "11"), UNKNOWN_ID);
        verifyNoInteractions(sources);
    }

    private SearchRequest request(int page) { return new SearchRequest("민법", 1, null, page, 1); }
    private LegalToolContext context(int budget) {
        return new LegalToolContext(LegalToolContext.Purpose.FIRST_QUESTION, "execution",
                clock.instant().plusSeconds(60), budget, clock);
    }
    private String lawPage(String id, int page, int total) {
        return """
                {"LawSearch":{"page":%d,"totalCnt":%d,"law":{"법령일련번호":"%s",
                "법령ID":"100","법령명한글":"민법","시행일자":"20260101"}}}
                """.formatted(page, total, id);
    }
    private JsonNode lawDetail(String lawId, String date) throws Exception {
        return mapper.readTree("""
                {"기본정보":{"법령ID":"%s","법령명_한글":"민법","시행일자":"%s"},
                 "조문":{"조문단위":[{"조문번호":"1","조문내용":"내용"}]}}
                """.formatted(lawId, date));
    }
    private void expectReason(ThrowingCallable action, LegalToolException.Reason reason) {
        assertThatThrownBy(action).isInstanceOfSatisfying(LegalToolException.class,
                error -> assertThat(error.getReason()).isEqualTo(reason));
    }
    private static class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return instant; }
    }
}
