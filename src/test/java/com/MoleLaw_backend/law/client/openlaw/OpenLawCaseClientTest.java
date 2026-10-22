package com.MoleLaw_backend.law.client.openlaw;

import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.law.dto.request.PrecedentSearchRequest;
import com.MoleLaw_backend.law.exception.OpenLawApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenLawCaseClientTest {
    private final AtomicReference<URI> requested = new AtomicReference<>();

    @Test
    void searchUsesQueryAndPageParametersAndPreservesSpecialCharacters() {
        OpenLawCaseClient client = client(HttpStatus.OK,
                "{\"PrecSearch\":{\"totalCnt\":\"1\",\"prec\":{\"판례일련번호\":\"12\"}}}");
        assertThat(client.search("손해 & + {배상}", 2, 3, 20).path("prec").isObject()).isTrue();
        var parameters = UriComponentsBuilder.fromUri(requested.get()).build().getQueryParams();
        assertThat(requested.get().getPath()).isEqualTo("/DRF/lawSearch.do");
        assertThat(parameters.getFirst("target")).isEqualTo("prec");
        assertThat(parameters.getFirst("search")).isEqualTo("2");
        assertThat(parameters.getFirst("page")).isEqualTo("3");
        assertThat(parameters.getFirst("display")).isEqualTo("20");
        assertThat(requested.get().getRawQuery()).contains("%26", "%2B", "%7B");
        assertThat(parameters).doesNotContainKey("JO");
    }

    @Test
    void zeroResultsRemainSuccessful() {
        assertThat(client(HttpStatus.OK, "{\"PrecSearch\":{\"totalCnt\":\"0\"}}")
                .search("없음", 1, 1, 20).path("totalCnt").asInt()).isZero();
    }

    @Test
    void detailUsesIdAndReturnsOriginalContent() {
        var result = client(HttpStatus.OK,
                "{\"PrecService\":{\"판례정보일련번호\":\"12\",\"판례내용\":\"원문\"}}")
                .detail("12");
        assertThat(result.path("판례내용").asText()).isEqualTo("원문");
        assertThat(requested.get().getPath()).isEqualTo("/DRF/lawService.do");
        assertThat(requested.get().getQuery()).contains("ID=12");
    }

    @Test
    void malformedAndErrorPayloadsAreNotEmptySearches() {
        for (String body : new String[]{"", "not-json", "{}", "{\"error\":\"invalid key\"}",
                "{\"PrecSearch\":{\"totalCnt\":1}}", "{\"PrecSearch\":{\"totalCnt\":\"error\"}}"}) {
            assertThatThrownBy(() -> client(HttpStatus.OK, body).search("검색", 1, 1, 20))
                    .isInstanceOfSatisfying(OpenLawApiException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.OPENLAW_INVALID_RESPONSE));
        }
    }

    @Test
    void detailRejectsWrongIdentityAndMissingContent() {
        for (String body : new String[]{"{\"PrecService\":{\"판례정보일련번호\":\"13\",\"판례내용\":\"원문\"}}",
                "{\"PrecService\":{\"판례정보일련번호\":\"12\"}}"}) {
            assertThatThrownBy(() -> client(HttpStatus.OK, body).detail("12"))
                    .isInstanceOfSatisfying(OpenLawApiException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.OPENLAW_INVALID_RESPONSE));
        }
    }

    @Test
    void httpFailureIsDistinctAndDoesNotExposeResponseOrKey() {
        assertThatThrownBy(() -> client(HttpStatus.SERVICE_UNAVAILABLE, "secret-body").search("검색", 1, 1, 20))
                .isInstanceOfSatisfying(OpenLawApiException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.OPENLAW_API_FAILURE);
                    assertThat(error.getMessage()).doesNotContain("secret-body", "test-key");
                    assertThat(error.getCause()).isNull();
                });
    }

    @Test
    void transportFailureUsesApiFailureWithoutLeakingRequestDetails() {
        var builder = WebClient.builder().exchangeFunction(request ->
                Mono.error(new IllegalStateException("request contained test-key")));
        var client = new OpenLawCaseClient(builder, new ObjectMapper(), "test-key");
        assertThatThrownBy(() -> client.search("검색", 1, 1, 20))
                .isInstanceOfSatisfying(OpenLawApiException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.OPENLAW_API_FAILURE);
                    assertThat(error.getMessage()).doesNotContain("test-key");
                    assertThat(error.getCause()).isNull();
                });
    }

    @Test
    void requestKeepsKeywordSeparateFromReferenceLawAndCourtFilters() {
        var parameters = new PrecedentSearchRequest();
        parameters.setQuery("손해배상");
        parameters.setReferenceLaw("민법");
        parameters.setCourtType("400201");
        parameters.setCourtName("대법원");
        client(HttpStatus.OK, "{\"PrecSearch\":{\"totalCnt\":0}}" ).search(parameters);
        var query = UriComponentsBuilder.fromUri(requested.get()).build().getQueryParams();
        assertThat(java.net.URLDecoder.decode(query.getFirst("query"), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("손해배상");
        assertThat(java.net.URLDecoder.decode(query.getFirst("JO"), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("민법");
        assertThat(query.getFirst("org")).isEqualTo("400201");
        assertThat(java.net.URLDecoder.decode(query.getFirst("curt"), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("대법원");
        assertThat(query.getFirst("search")).isEqualTo("1");
        assertThat(query.getFirst("page")).isEqualTo("1");
        assertThat(query.getFirst("display")).isEqualTo("5");
    }

    @Test
    void emptyOutOfRangePageIsValidButMissingInRangeRowsAreInvalid() {
        var client = client(HttpStatus.OK, "{\"PrecSearch\":{\"totalCnt\":5}}" );
        assertThat(client.search("검색", 1, 2, 5).path("totalCnt").asInt()).isEqualTo(5);
        assertThatThrownBy(() -> client.search("검색", 1, 1, 5))
                .isInstanceOfSatisfying(OpenLawApiException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.OPENLAW_INVALID_RESPONSE));
    }

    @Test
    void oversizedCountCannotOverflowIntoEmptySearch() {
        assertThatThrownBy(() -> client(HttpStatus.OK,
                "{\"PrecSearch\":{\"totalCnt\":\"1000000000\"}}" ).search("검색", 1, 1, 5))
                .isInstanceOfSatisfying(OpenLawApiException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.OPENLAW_INVALID_RESPONSE));
    }

    @Test
    void invalidSearchAndDetailInputsNeverReachHttp() {
        var client = client(HttpStatus.OK, "{\"PrecSearch\":{\"totalCnt\":0}}" );
        for (String id : new String[]{null, "", "12&OC=other", "123456789012345678901"}) {
            assertInvalid(() -> client.detail(id));
        }
        assertInvalid(() -> client.search((PrecedentSearchRequest) null));
        assertInvalid(() -> client.search(" ", 1, 1, 5));
        assertInvalid(() -> client.search("검색", 3, 1, 5));
        assertInvalid(() -> client.search("검색", 1, 0, 5));
        assertInvalid(() -> client.search("검색", 1, 1, 101));
        var parameters = new PrecedentSearchRequest();
        parameters.setQuery("검색");
        parameters.setCourtType("400203");
        assertInvalid(() -> client.search(parameters));
        assertThat(requested.get()).isNull();
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(MolelawException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    private OpenLawCaseClient client(HttpStatus status, String body) {
        var builder = WebClient.builder().exchangeFunction(request -> {
            requested.set(request.url());
            return Mono.just(ClientResponse.create(status)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build());
        });
        return new OpenLawCaseClient(builder, new ObjectMapper(), "test-key");
    }
}
