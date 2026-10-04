package com.MoleLaw_backend.service.law;

import com.MoleLaw_backend.domain.entity.Law;
import com.MoleLaw_backend.domain.entity.LawChunk;
import com.MoleLaw_backend.domain.repository.LawChunkRepository;
import com.MoleLaw_backend.domain.repository.LawRepository;
import com.MoleLaw_backend.exception.OpenLawApiException;
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
    private LawRepository lawRepository;
    private LawChunkRepository chunkRepository;
    private LawSearchService service;

    @BeforeEach
    void setUp() {
        lawRepository = mock(LawRepository.class);
        chunkRepository = mock(LawChunkRepository.class);
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            return Mono.just(responses.removeFirst());
        });
        service = new LawSearchService(builder, lawRepository, chunkRepository, new ObjectMapper());
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
    void wrapsApiFailureWithoutContinuingFallbackSearch() {
        responses.add(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());

        assertThrows(OpenLawApiException.class, () -> service.searchLawByKeyword("테스트법", "100"));
        assertEquals(1, requests.size());
        verifyNoInteractions(lawRepository, chunkRepository);
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

    @Test
    void savesArticleHoAndMokFromSyntheticDetailFixture() throws IOException {
        enqueue(detailFixture());
        Law law = Law.builder().id(1L).name("테스트법").lawCode("test-law").build();
        when(lawRepository.findByNameAndLawCode("테스트법", "test-law")).thenReturn(Optional.of(law));
        when(chunkRepository.findByLawAndArticleNumberAndClauseNumber(eq(law), anyString(), nullable(String.class)))
                .thenReturn(Optional.empty());

        assertSame(law, service.saveSingleLawWithArticles("123"));

        ArgumentCaptor<LawChunk> chunks = ArgumentCaptor.forClass(LawChunk.class);
        verify(chunkRepository, times(3)).save(chunks.capture());
        List<LawChunk> saved = chunks.getAllValues();
        assertEquals(List.of("제1조 테스트 조문", "테스트 호", "테스트 목"),
                saved.stream().map(LawChunk::getContentText).toList());
        assertNull(saved.get(0).getClauseNumber());
        assertEquals("1", saved.get(1).getClauseNumber());
        assertEquals("1-가", saved.get(2).getClauseNumber());
        assertEquals(List.of(0, 1, 2), saved.stream().map(LawChunk::getChunkIndex).toList());
        assertTrue(saved.stream().allMatch(chunk -> chunk.getLaw() == law && "1".equals(chunk.getArticleNumber())));
        verify(lawRepository, never()).save(any());
    }

    @Test
    void repeatedCollectionDoesNotSaveUnchangedChunks() throws IOException {
        enqueue(detailFixture());
        Law law = Law.builder().id(1L).name("테스트법").lawCode("test-law").build();
        when(lawRepository.findByNameAndLawCode("테스트법", "test-law")).thenReturn(Optional.of(law));
        when(chunkRepository.findByLawAndArticleNumberAndClauseNumber(law, "1", null))
                .thenReturn(Optional.of(LawChunk.builder().contentText("제1조 테스트 조문").build()));
        when(chunkRepository.findByLawAndArticleNumberAndClauseNumber(law, "1", "1"))
                .thenReturn(Optional.of(LawChunk.builder().contentText("테스트 호").build()));
        when(chunkRepository.findByLawAndArticleNumberAndClauseNumber(law, "1", "1-가"))
                .thenReturn(Optional.of(LawChunk.builder().contentText("테스트 목").build()));

        service.saveSingleLawWithArticles("123");

        verify(chunkRepository, never()).save(any());
    }

    private void enqueue(String... bodies) {
        for (String body : bodies) {
            responses.add(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build());
        }
    }

    private void assertSearch(int index, String search, String org) {
        assertEquals("/DRF/lawSearch.do", requests.get(index).url().getPath());
        assertEquals("law", parameter(index, "target"));
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
