package com.MoleLaw_backend.law.client.openlaw;

import com.MoleLaw_backend.law.exception.OpenLawRequestException;

import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.law.dto.request.PrecedentSearchRequest;
import com.MoleLaw_backend.law.exception.OpenLawApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;
import java.util.LinkedHashMap;

/** Bounded transport for the official precedent list and detail APIs. */
@Component
public class OpenLawCaseClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public OpenLawCaseClient(WebClient.Builder builder, ObjectMapper objectMapper,
                             @Value("${openlaw.api-key}") String apiKey) {
        this.webClient = builder.clone()
                .codecs(config -> config.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
    }

    public JsonNode search(String query, int search, int page, int display) {
        PrecedentSearchRequest parameters = new PrecedentSearchRequest();
        parameters.setQuery(query);
        parameters.setSearch(search);
        parameters.setPage(page);
        parameters.setDisplay(display);
        return search(parameters);
    }

    public JsonNode search(PrecedentSearchRequest parameters) {
        if (parameters == null) throw new MolelawException(ErrorCode.INVALID_REQUEST);
        parameters.validate();
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("query", parameters.getQuery());
        query.put("search", parameters.getSearch());
        query.put("page", parameters.getPage());
        query.put("display", parameters.getDisplay());
        if (parameters.getReferenceLaw() != null) query.put("JO", parameters.getReferenceLaw());
        if (parameters.getCourtType() != null) query.put("org", parameters.getCourtType());
        if (parameters.getCourtName() != null) query.put("curt", parameters.getCourtName());
        JsonNode result = request("lawSearch.do", "PrecSearch", query);
        JsonNode count = result.path("totalCnt");
        if (!count.asText().matches("[0-9]{1,9}")) {
            throw invalidResponse();
        }
        JsonNode items = result.path("prec");
        if (!(items.isMissingNode() || items.isNull() || items.isArray() || items.isObject())) {
            throw invalidResponse();
        }
        int itemCount = items.isObject() ? (items.isEmpty() ? 0 : 1) : items.isArray() ? items.size() : 0;
        if (itemCount > parameters.getDisplay() || (count.asLong() == 0 && itemCount > 0)
                || (result.has("page") && result.path("page").asInt(-1) != parameters.getPage())) {
            throw invalidResponse();
        }
        if (count.asLong() > 0 && (items.isMissingNode() || items.isNull() || items.isEmpty())
                && (long) (parameters.getPage() - 1) * parameters.getDisplay() < count.asLong()) {
            throw invalidResponse();
        }
        return result;
    }

    public JsonNode detail(String id) {
        if (id == null || !id.matches("[0-9]{1,20}")) {
            throw new MolelawException(ErrorCode.INVALID_REQUEST);
        }
        JsonNode result = request("lawService.do", "PrecService", Map.of("ID", id));
        if (!id.equals(result.path("판례정보일련번호").asText())
                || result.path("판례내용").asText().isBlank()) {
            throw invalidResponse();
        }
        return result;
    }

    private JsonNode request(String endpoint, String rootName, Map<String, ?> parameters) {
        String body;
        try {
            body = webClient.get().uri(builder -> {
                builder.scheme("https").host("www.law.go.kr").path("/DRF/" + endpoint)
                        .queryParam("OC", apiKey).queryParam("target", "prec").queryParam("type", "JSON");
                // URI variables ensure query text containing '&', '+', or braces stays data.
                parameters.forEach((name, value) -> builder.queryParam(name, "{" + name + "}"));
                return builder.build(parameters);
            }).retrieve().bodyToMono(String.class).block(TIMEOUT);
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException exception) {
            int status = exception.getStatusCode().value();
            throw new OpenLawRequestException(status == 429 || status >= 500);
        } catch (RuntimeException exception) {
            throw new OpenLawRequestException(true);
        }
        if (body == null || body.isBlank()) {
            throw invalidResponse();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode result = root == null ? null : root.get(rootName);
            if (result == null || !result.isObject() || result.isEmpty()) {
                throw invalidResponse();
            }
            return result;
        } catch (JsonProcessingException exception) {
            throw invalidResponse();
        }
    }

    private OpenLawApiException invalidResponse() {
        return new OpenLawApiException(ErrorCode.OPENLAW_INVALID_RESPONSE);
    }
}
