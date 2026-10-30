package com.MoleLaw_backend.law.controller;

import com.MoleLaw_backend.common.exception.GlobalExceptionHandler;
import com.MoleLaw_backend.law.service.search.CaseSearchService;
import com.MoleLaw_backend.law.service.analysis.KeywordExtractionService;
import com.MoleLaw_backend.chat.service.answer.LegalAnswerService;
import com.MoleLaw_backend.law.service.search.LawSearchService;
import com.MoleLaw_backend.law.client.openlaw.OpenLawCaseClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PrecedentControllerTest {
    private final ArrayDeque<ClientResponse> responses = new ArrayDeque<>();
    private final List<URI> requests = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenLawCaseClient client = new OpenLawCaseClient(WebClient.builder().exchangeFunction(request -> {
        requests.add(request.url());
        return Mono.just(responses.removeFirst());
    }), mapper, "test-key");
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PrecedentController(new CaseSearchService(client, mapper)))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void listBindsFiltersAndReturnsRealKoreanFields() throws Exception {
        enqueue("""
                {"PrecSearch":{"totalCnt":"1","prec":{"판례일련번호":"123","사건명":"손해배상",
                "사건번호":"2025다1","선고일자":"20260101","법원명":"대법원","법원종류코드":"400201"}}}
                """);
        mvc.perform(get("/api/precedents").param("query", "손해배상").param("referenceLaw", "민법")
                        .param("courtType", "400201").param("courtName", "대법원")
                        .param("search", "2").param("page", "1").param("display", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].판례일련번호").value("123"))
                .andExpect(jsonPath("$[0].사건명").value("손해배상"));
        var parameters = UriComponentsBuilder.fromUri(requests.get(0)).build().getQueryParams();
        assertEquals("손해배상", decode(parameters.getFirst("query")));
        assertEquals("민법", decode(parameters.getFirst("JO")));
        assertEquals("400201", parameters.getFirst("org"));
        assertEquals("2", parameters.getFirst("search"));
        assertEquals("10", parameters.getFirst("display"));
        assertEquals("https", requests.get(0).getScheme());
    }

    @Test
    void detailReturnsBodyAndReferenceMetadata() throws Exception {
        enqueue("""
                {"PrecService":{"판례정보일련번호":"123","사건명":"손해배상","사건번호":"2025다1",
                "판시사항":"쟁점","판결요지":"요지","참조조문":"민법 제750조","판례내용":"판결 본문"}}
                """);
        mvc.perform(get("/api/precedents/123")).andExpect(status().isOk())
                .andExpect(jsonPath("$.판례일련번호").value("123"))
                .andExpect(jsonPath("$.판시사항").value("쟁점"))
                .andExpect(jsonPath("$.판례내용").value("판결 본문"))
                .andExpect(jsonPath("$.판례상세링크").value("https://www.law.go.kr/precInfoP.do?precSeq=123"));
    }

    @Test
    void invalidRequestsNeverReachExternalApi() throws Exception {
        for (String query : new String[]{"", " ", "x".repeat(201)}) {
            mvc.perform(get("/api/precedents").param("query", query)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        mvc.perform(get("/api/precedents")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/precedents").param("query", "퇴직금").param("page", "abc"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/precedents").param("query", "퇴직금").param("display", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/precedents/invalid")).andExpect(status().isBadRequest());
        assertTrue(requests.isEmpty());
    }

    @Test
    void normalEmptyAndExternalFailureAreDistinct() throws Exception {
        enqueue("{\"PrecSearch\":{\"totalCnt\":0}}");
        mvc.perform(get("/api/precedents").param("query", "없음"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        responses.add(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).body("secret-body").build());
        mvc.perform(get("/api/precedents").param("query", "퇴직금"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.error.code").value("OPENLAW_API_FAILURE"));
        enqueue("{\"PrecService\":{\"판례정보일련번호\":\"123\"}}");
        mvc.perform(get("/api/precedents/123")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("OPENLAW_INVALID_RESPONSE"));
    }

    private void enqueue(String body) {
        responses.add(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(body).build());
    }

    private String decode(String value) {
        return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
