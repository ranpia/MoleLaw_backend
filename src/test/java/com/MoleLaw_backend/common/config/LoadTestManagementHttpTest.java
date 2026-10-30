package com.MoleLaw_backend.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "management.health.redis.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:management-test;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER"
})
@ActiveProfiles({"test", "loadtest"})
@AutoConfigureObservability
@Import(TestRefreshTokenStoreConfig.class)
class LoadTestManagementHttpTest {
    @MockitoBean ChatModel chatModel;
    @MockitoBean EmbeddingModel embeddingModel;
    @Autowired TestRestTemplate http;
    @LocalServerPort int applicationPort;
    @LocalManagementPort int managementPort;

    @Test
    void metricsAreReadOnlyOnSeparatePortAndApplicationSecurityStillApplies() {
        assertThat(managementPort).isNotEqualTo(applicationPort);
        String app = "http://127.0.0.1:" + applicationPort;
        String management = "http://127.0.0.1:" + managementPort;

        assertThat(http.getForEntity(app + "/api/auth/csrf", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        var scrape = http.getForEntity(management + "/actuator/prometheus", String.class);
        assertThat(scrape.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(scrape.getBody()).contains("jvm_memory_used_bytes", "hikaricp_connections",
                "http_server_requests_seconds_bucket", "molelaw-loadtest");
        assertThat(http.getForEntity(management + "/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity(app + "/actuator/prometheus", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.getForEntity(app + "/api/chat-rooms", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.postForEntity(app + "/api/auth/login", "{}", String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.getForEntity(management + "/actuator/env", String.class).getStatusCode().is2xxSuccessful())
                .isFalse();
        assertThat(http.postForEntity(management + "/actuator/prometheus", "", String.class)
                .getStatusCode().is2xxSuccessful()).isFalse();
    }
}
