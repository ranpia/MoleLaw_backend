package com.MoleLaw_backend.law.client.openlaw;

import com.MoleLaw_backend.law.dto.tool.AiLawSearchRequest;
import com.MoleLaw_backend.law.dto.retrieval.AiLawArticlePage;
import com.MoleLaw_backend.law.exception.LegalToolException;
import com.MoleLaw_backend.law.util.OpenLawAiSearchParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import lombok.extern.slf4j.Slf4j;
import java.time.Duration;
import java.util.Map;

@Component
@Slf4j
public class OpenLawAiSearchClient {
    private final WebClient client;
    private final ObjectMapper mapper;
    private final String apiKey;

    public OpenLawAiSearchClient(WebClient.Builder builder, ObjectMapper mapper,
            @Value("${openlaw.api-key}") String apiKey,
            @Value("${app.law-ai-search.base-url:https://www.law.go.kr/DRF}") String baseUrl) {
        this.client = builder.clone().baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024)).build();
        this.mapper = mapper;
        this.apiKey = apiKey;
    }

    public AiLawArticlePage search(AiLawSearchRequest request) {
        try {
            String body = client.get().uri(b -> b.path("/lawSearch.do")
                    .queryParam("OC", "{oc}").queryParam("target", "aiSearch").queryParam("type", "JSON")
                    .queryParam("search", 0).queryParam("query", "{query}")
                    .queryParam("page", request.page()).queryParam("display", request.display())
                    .build(Map.of("oc", apiKey, "query", request.query())))
                    .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(12));
            if (body == null || body.isBlank()) throw new LegalToolException(LegalToolException.Reason.INVALID_RESPONSE, false);
            return OpenLawAiSearchParser.parse(mapper.readTree(body), request);
        } catch (LegalToolException known) { throw known; }
        catch (WebClientResponseException failure) {
            int status = failure.getStatusCode().value();
            log.warn("OpenLaw aiSearch failed httpStatus={}", status);
            throw new LegalToolException(LegalToolException.Reason.API_FAILURE, status == 429 || status >= 500);
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            throw new LegalToolException(LegalToolException.Reason.INVALID_RESPONSE, false);
        } catch (RuntimeException transport) {
            throw new LegalToolException(LegalToolException.Reason.API_FAILURE, true);
        }
    }
}
