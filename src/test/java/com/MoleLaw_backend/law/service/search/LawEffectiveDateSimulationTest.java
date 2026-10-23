package com.MoleLaw_backend.law.service.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import java.net.URI;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("postman-simulation")
@EnabledIfEnvironmentVariable(named = "LAW_EFFECTIVE_DATE_SIMULATION_URL", matches = "http://127\\.0\\.0\\.1:[0-9]+")
class LawEffectiveDateSimulationTest {
    @Test
    void actualHttpCarriesTheListsEffectiveDateInsteadOfReturningTheOtherStage() {
        String base = System.getenv("LAW_EFFECTIVE_DATE_SIMULATION_URL");
        var transport = WebClient.builder().filter((request, next) -> {
            var original = request.url();
            var fixture = URI.create(base + original.getRawPath() + "?" + original.getRawQuery());
            return next.exchange(ClientRequest.from(request).url(fixture).build());
        });
        var service = new LawSearchService(transport, new ObjectMapper());
        ReflectionTestUtils.setField(service, "oc", "synthetic-test-key");
        assertThat(service.getLawDetailByMst("289515").path("기본정보").path("시행일자").asText())
                .isEqualTo("20261209");
        assertThat(service.getLawDetailByMst("289515", "20270309").path("기본정보").path("시행일자").asText())
                .isEqualTo("20270309");
    }
}
