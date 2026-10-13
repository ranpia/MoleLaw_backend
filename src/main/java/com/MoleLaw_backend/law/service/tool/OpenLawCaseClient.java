package com.MoleLaw_backend.law.service.tool;

import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.law.exception.OpenLawApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

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
        JsonNode result = request("lawSearch.do", "PrecSearch", Map.of(
                "query", query, "search", search, "page", page, "display", display));
        JsonNode count = result.path("totalCnt");
        if (!count.asText().matches("[0-9]+")) {
            throw invalidResponse();
        }
        JsonNode items = result.path("prec");
        if (!(items.isMissingNode() || items.isNull() || items.isArray() || items.isObject())) {
            throw invalidResponse();
        }
        if (count.asLong() > 0 && (items.isMissingNode() || items.isNull() || items.isEmpty())) {
            throw invalidResponse();
        }
        return result;
    }

    public JsonNode detail(String id) {
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
