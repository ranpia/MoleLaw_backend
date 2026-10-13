package com.MoleLaw_backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BrowserErrorDispatchTest {
    @MockitoBean ChatModel chatModel;
    @MockitoBean EmbeddingModel embeddingModel;
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;

    @Test
    void swaggerSameOriginWithCsrfReachesLoginAndFilterFailuresRemain403() throws Exception {
        String base = "http://localhost:" + port;
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        var bootstrap = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, bootstrap.statusCode());
        String token = mapper.readTree(bootstrap.body()).path("token").asText();
        assertFalse(token.isBlank());
        var sameOrigin = client.send(login(base).header("Origin", base).header("X-XSRF-TOKEN", token).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(502, sameOrigin.statusCode());
        assertEquals("USER_NOT_FOUND", mapper.readTree(sameOrigin.body()).path("error").path("code").asText());
        var missingCsrf = client.send(login(base).header("Origin", base).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, missingCsrf.statusCode());
        var externalOrigin = client.send(login(base).header("Origin", "https://attacker.example")
                .header("X-XSRF-TOKEN", token).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, externalOrigin.statusCode());
        var protectedRequest = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/me")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, protectedRequest.statusCode());
        var directError = client.send(HttpRequest.newBuilder(URI.create(base + "/error")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, directError.statusCode());
    }

    private HttpRequest.Builder login(String base) {
        return HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"missing@example.invalid\",\"password\":\"test-only\"}"));
    }
}
