package com.MoleLaw_backend.law.service;

import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.law.dto.PrecedentInfo;
import com.MoleLaw_backend.law.dto.request.PrecedentSearchRequest;
import com.MoleLaw_backend.law.dto.response.PrecedentDetailResponse;
import com.MoleLaw_backend.law.exception.OpenLawApiException;
import com.MoleLaw_backend.law.service.tool.OpenLawCaseClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CaseSearchServiceTest {
    private final OpenLawCaseClient client = mock(OpenLawCaseClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final CaseSearchService service = new CaseSearchServiceImpl(client, mapper);

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mapsArrayAndSingleObjectKoreanFieldsIgnoringUnknownProperties(boolean array) throws Exception {
        PrecedentSearchRequest request = request();
        String row = """
                {"판례일련번호":"123","사건명":"손해배상","사건번호":"2024다123",
                 "선고일자":"2024.01.15","법원명":"대법원","사건종류명":"민사",
                 "판결유형":"판결","선고":"선고","판례상세링크":"/detail/123","추가필드":"무시"}
                """;
        when(client.search(request)).thenReturn(mapper.readTree("{\"prec\":" + (array ? "[" + row + "]" : row) + "}"));

        List<PrecedentInfo> result = service.searchCases(request);

        assertThat(result).hasSize(1);
        PrecedentInfo info = result.get(0);
        assertThat(info.getCaseId()).isEqualTo("123");
        assertThat(info.getCaseName()).isEqualTo("손해배상");
        assertThat(info.getCaseNumber()).isEqualTo("2024다123");
        assertThat(info.getDecisionDate()).isEqualTo("2024.01.15");
        assertThat(info.getCourtName()).isEqualTo("대법원");
        assertThat(info.getCaseType()).isEqualTo("민사");
        assertThat(info.getDecisionType()).isEqualTo("판결");
        assertThat(info.getDecisionResult()).isEqualTo("선고");
        assertThat(info.getDetailLink()).isEqualTo("/detail/123");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"prec\":null}", "{\"prec\":[]}"})
    void returnsEmptyListForNormalEmptyResults(String json) throws Exception {
        PrecedentSearchRequest request = request();
        when(client.search(request)).thenReturn(mapper.readTree(json));
        assertThat(service.searchCases(request)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"판례일련번호\":\"invalid\",\"사건명\":\"손해배상\"}",
            "{\"사건명\":\"손해배상\"}",
            "{\"판례일련번호\":\"123\",\"사건명\":\" \"}",
            "{\"판례일련번호\":\"123\"}", "null", "5"
    })
    void rejectsInvalidListIdentityOrName(String row) throws Exception {
        PrecedentSearchRequest request = request();
        when(client.search(request)).thenReturn(mapper.readTree("{\"prec\":[" + row + "]}"));
        assertInvalidResponse(() -> service.searchCases(request));
    }

    @Test
    void mapsDetailIdentityAliasAndOriginalContentAndBuildsOfficialLink() throws Exception {
        when(client.detail("123")).thenReturn(mapper.readTree("""
                {"판례정보일련번호":"123","사건명":"손해배상","판시사항":"쟁점",
                 "판결요지":"요지","참조조문":"민법 제750조","참조판례":"2020다1",
                 "판례내용":"주문<br/>이유","판례상세링크":"https://untrusted.example","미지필드":true}
                """));

        PrecedentDetailResponse detail = service.getCaseDetail("123");

        assertThat(detail.getCaseId()).isEqualTo("123");
        assertThat(detail.getIssues()).isEqualTo("쟁점");
        assertThat(detail.getSummary()).isEqualTo("요지");
        assertThat(detail.getReferencedArticles()).isEqualTo("민법 제750조");
        assertThat(detail.getReferencedCases()).isEqualTo("2020다1");
        assertThat(detail.getContent()).isEqualTo("주문<br/>이유");
        assertThat(detail.getDetailLink()).isEqualTo("https://www.law.go.kr/precInfoP.do?precSeq=123");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"판례정보일련번호\":\"999\",\"사건명\":\"사건\",\"판례내용\":\"본문\"}",
            "{\"판례정보일련번호\":\"123\",\"사건명\":\"사건\",\"판례내용\":\" \"}",
            "{\"판례정보일련번호\":\"123\",\"사건명\":\"사건\"}",
            "{\"판례정보일련번호\":\"123\",\"판례내용\":\"본문\"}", "null"
    })
    void rejectsDetailWithWrongIdentityOrMissingNameOrBody(String json) throws Exception {
        when(client.detail("123")).thenReturn(mapper.readTree(json));
        assertInvalidResponse(() -> service.getCaseDetail("123"));
    }

    @Test
    void rejectsInvalidQueryPaginationAndFiltersBeforeCallingClient() {
        List<Consumer<PrecedentSearchRequest>> invalidInputs = List.of(
                r -> r.setQuery(null), r -> r.setQuery(" "), r -> r.setQuery("a".repeat(201)),
                r -> r.setPage(0), r -> r.setPage(10001), r -> r.setDisplay(0), r -> r.setDisplay(101),
                r -> r.setSearch(3), r -> r.setReferenceLaw(" "), r -> r.setReferenceLaw("a".repeat(201)),
                r -> r.setCourtType("999"), r -> r.setCourtName(" "), r -> r.setCourtName("a".repeat(101)));
        for (Consumer<PrecedentSearchRequest> invalidInput : invalidInputs) {
            PrecedentSearchRequest request = request();
            invalidInput.accept(request);
            assertInvalidRequest(() -> service.searchCases(request));
        }
        assertInvalidRequest(() -> service.searchCases(null));
        for (String id : new String[]{null, "", "-1", "123&OC=key", "1".repeat(21)}) {
            assertInvalidRequest(() -> service.getCaseDetail(id));
        }
        verifyNoInteractions(client);
    }

    @Test
    void propagatesApiFailureWithoutConvertingItToEmptyResult() {
        PrecedentSearchRequest request = request();
        OpenLawApiException failure = new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE);
        when(client.search(request)).thenThrow(failure);
        when(client.detail("123")).thenThrow(failure);
        assertThatThrownBy(() -> service.searchCases(request)).isSameAs(failure);
        assertThatThrownBy(() -> service.getCaseDetail("123")).isSameAs(failure);
    }

    private static PrecedentSearchRequest request() {
        PrecedentSearchRequest request = new PrecedentSearchRequest();
        request.setQuery("손해배상");
        return request;
    }

    private static void assertInvalidResponse(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(OpenLawApiException.class)
                .satisfies(error -> assertThat(((OpenLawApiException) error).getErrorCode())
                        .isEqualTo(ErrorCode.OPENLAW_INVALID_RESPONSE));
    }

    private static void assertInvalidRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(MolelawException.class)
                .satisfies(error -> assertThat(((MolelawException) error).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));
    }
}
