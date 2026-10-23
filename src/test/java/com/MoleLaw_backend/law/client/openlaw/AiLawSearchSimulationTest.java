package com.MoleLaw_backend.law.client.openlaw;

import com.MoleLaw_backend.law.dto.tool.AiLawSearchRequest;
import com.MoleLaw_backend.law.exception.LegalToolException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.reactive.function.client.WebClient;
import static org.assertj.core.api.Assertions.*;

@Tag("postman-simulation")
@EnabledIfEnvironmentVariable(named = "LAW_AI_SEARCH_SIMULATION_URL", matches = "http://127\\.0\\.0\\.1:[0-9]+")
class AiLawSearchSimulationTest {
    private final OpenLawAiSearchClient client = new OpenLawAiSearchClient(WebClient.builder(), new ObjectMapper(),
            "synthetic-key", System.getenv("LAW_AI_SEARCH_SIMULATION_URL") + "/DRF");

    @Test void sendsArticleScopeEscapesQueryAndSupportsPagination() {
        var first = client.search(new AiLawSearchRequest("공항 & 높이+{}", 1, 15));
        var second = client.search(new AiLawSearchRequest("공항 & 높이+{}", 2, 15));
        assertThat(first.hasNext()).isTrue();
        assertThat(second.hasNext()).isFalse();
        assertThat(first.articles().get(0).articleId()).isNotEqualTo(second.articles().get(0).articleId());
    }

    @Test void distinguishesEmptyResultsFrom404AndMalformed200WithoutFallback() {
        assertThat(client.search(new AiLawSearchRequest("empty", 1, 15)).articles()).isEmpty();
        assertThatThrownBy(() -> client.search(new AiLawSearchRequest("denied", 1, 15)))
                .isInstanceOfSatisfying(LegalToolException.class, error -> {
                    assertThat(error.getReason()).isEqualTo(LegalToolException.Reason.API_FAILURE);
                    assertThat(error.isRetryable()).isFalse();
                });
        assertThatThrownBy(() -> client.search(new AiLawSearchRequest("broken", 1, 15)))
                .isInstanceOfSatisfying(LegalToolException.class,
                        error -> assertThat(error.getReason()).isEqualTo(LegalToolException.Reason.INVALID_RESPONSE));
    }
}
