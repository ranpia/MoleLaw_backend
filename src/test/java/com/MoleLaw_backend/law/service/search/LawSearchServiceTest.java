package com.MoleLaw_backend.law.service.search;

import com.MoleLaw_backend.law.exception.OpenLawApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LawSearchServiceTest {
    private static final String EMPTY = "{\"LawSearch\":{\"totalCnt\":0}}";
    private static final String FOUND = "{\"LawSearch\":{\"law\":[{\"법령명한글\":\"테스트법\",\"법령일련번호\":\"123\"}]}}";

    private final List<ClientRequest> requests = new ArrayList<>();
    private final ArrayDeque<ClientResponse> responses = new ArrayDeque<>();
    private LawSearchService service;

    @BeforeEach
    void setUp() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            return Mono.just(responses.removeFirst());
        });
        service = new LawSearchService(builder, new ObjectMapper());
        ReflectionTestUtils.setField(service, "oc", "test-key");
    }

    @Test
    void stopsWhenMinistryTitleSearchFindsResults() {
        enqueue(FOUND);

        assertEquals(FOUND, service.searchLawByKeyword("테스트법", "100"));

        assertEquals(1, requests.size());
        assertSearch(0, "1", "100");
    }

    @Test
    void fallsBackFromMinistryTitleToMinistryBodyThenUnfilteredTitleAndBody() {
        enqueue(EMPTY, EMPTY, EMPTY, FOUND);

        assertEquals(FOUND, service.searchLawByKeyword("테스트법", "100"));

        assertEquals(4, requests.size());
        assertSearch(0, "1", "100");
        assertSearch(1, "2", "100");
        assertSearch(2, "1", null);
        assertSearch(3, "2", null);
    }

    @Test
    void returnsEmptyResultAfterBothUnfilteredSearches() {
        enqueue(EMPTY, EMPTY);

        assertEquals(EMPTY, service.searchLawByKeyword("테스트법"));
        assertEquals(2, requests.size());
        assertSearch(0, "1", null);
        assertSearch(1, "2", null);
    }

    @Test
    void extractsOnlyNonblankMstValues() {
        enqueue("{\"LawSearch\":{\"law\":[{\"법령명한글\":\"테스트법\",\"법령일련번호\":\"123\"},{\"법령명한글\":\"빈 식별자\",\"법령일련번호\":\"\"}]}}");

        assertEquals(List.of("123"), service.getLawMstByName("테스트법"));
    }

    @Test
    void explicitPagePreservesFiltersAndEncodesQueryAsData() {
        enqueue(FOUND);
        service.searchPage("민법&org=999+{x}", 2, "100", 3, 50);
        assertEquals("3", parameter(0, "page"));
        assertEquals("50", parameter(0, "display"));
        assertEquals("2", parameter(0, "search"));
        assertEquals("100", parameter(0, "org"));
        assertEquals("https", requests.get(0).url().getScheme());
        assertEquals("민법&org=999+{x}", java.net.URLDecoder.decode(parameter(0, "query"), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsInvalidPageBeforeExternalCall() {
        assertThrows(IllegalArgumentException.class, () -> service.searchPage("민법", 1, null, 0, 20));
        assertThrows(IllegalArgumentException.class, () -> service.searchPage("민법", 1, null, 1, 101));
        assertTrue(requests.isEmpty());
    }

    @Test
    void passesLawIdentityKindAndCurrentStatusToOfficialApi() {
        enqueue(FOUND);
        service.searchPage("민법", 1, "1270000", 1, 20,
                new com.MoleLaw_backend.law.dto.request.LawSearchFilters("001706", "A0002", 3, true));
        assertEquals("001706", parameter(0, "LID"));
        assertEquals("A0002", parameter(0, "knd"));
        assertEquals("3", parameter(0, "nw"));
        assertEquals("1270000", parameter(0, "org"));
    }

    @Test
    void exactTitleSearchEncodesQuotedQueryAndOptionalDateAndSortFilters() {
        enqueue(FOUND);
        var filters = new com.MoleLaw_backend.law.dto.request.LawSearchFilters(
                "001706", "A0002", 3, true, "efdes", "20200101~20261231", "20100101~20261231");
        service.searchPage("민법", 1, "1270000", 2, 50, filters);
        assertEquals("\"민법\"", java.net.URLDecoder.decode(parameter(0, "query"), StandardCharsets.UTF_8));
        assertEquals("001706", parameter(0, "LID"));
        assertEquals("A0002", parameter(0, "knd"));
        assertEquals("3", parameter(0, "nw"));
        assertEquals("efdes", parameter(0, "sort"));
        assertEquals("20200101~20261231", parameter(0, "efYd"));
        assertEquals("20100101~20261231", parameter(0, "ancYd"));
    }

    @Test
    void rejectsInvalidSortAndCalendarRanges() {
        for (String sort : new String[]{"unknown", "lasc&OC=other", ""}) {
            assertThrows(IllegalArgumentException.class, () -> new com.MoleLaw_backend.law.dto.request.LawSearchFilters(
                    null, null, null, false, sort, null, null));
        }
        for (String range : new String[]{"20260230~20260301", "20261231~20260101", "20260101", ""}) {
            assertThrows(IllegalArgumentException.class, () -> new com.MoleLaw_backend.law.dto.request.LawSearchFilters(
                    null, null, null, false, null, range, null));
            assertThrows(IllegalArgumentException.class, () -> new com.MoleLaw_backend.law.dto.request.LawSearchFilters(
                    null, null, null, false, null, null, range));
        }
        assertTrue(requests.isEmpty());
    }

    @Test
    void wrapsApiFailureWithoutContinuingFallbackSearch() {
        responses.add(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());

        assertThrows(OpenLawApiException.class, () -> service.searchLawByKeyword("테스트법", "100"));
        assertEquals(1, requests.size());
    }

    @Test
    void fetchesDetailByMstAndRejectsMissingLawNode() {
        enqueue("{}");

        assertThrows(OpenLawApiException.class, () -> service.getLawDetailByMst("123"));
        assertEquals("/DRF/lawService.do", requests.get(0).url().getPath());
        assertEquals("123", parameter(0, "MST"));
        assertEquals("law", parameter(0, "target"));
        assertEquals("JSON", parameter(0, "type"));
        assertEquals("test-key", parameter(0, "OC"));
    }

    private void enqueue(String... bodies) {
        for (String body : bodies) {
            responses.add(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build());
        }
    }

    private void assertSearch(int index, String search, String org) {
        assertEquals("/DRF/lawSearch.do", requests.get(index).url().getPath());
        assertEquals("eflaw", parameter(index, "target"));
        assertEquals("JSON", parameter(index, "type"));
        assertEquals("test-key", parameter(index, "OC"));
        assertEquals(search, parameter(index, "search"));
        assertEquals(org, parameter(index, "org"));
        assertEquals("20", parameter(index, "display"));
        assertEquals("lasc", parameter(index, "sort"));
        assertEquals("테스트법", java.net.URLDecoder.decode(parameter(index, "query"), StandardCharsets.UTF_8));
    }

    private String parameter(int index, String name) {
        return UriComponentsBuilder.fromUri(requests.get(index).url()).build().getQueryParams().getFirst(name);
    }

    private String detailFixture() throws IOException {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/fixtures/openlaw/law-detail.json"))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
