package com.MoleLaw_backend.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.MoleLaw_backend.config.TestAccessTokenStoreConfig.class)
@ActiveProfiles("test")
class OpenApiDocumentationTest {
    @MockitoBean ChatModel chatModel;
    @MockitoBean EmbeddingModel embeddingModel;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void everyControllerOperationHasUsableDocumentationAndCorrectSecurity() throws Exception {
        JsonNode document = mapper.readTree(mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        Set<String> verbs = Set.of("get", "post", "put", "patch", "delete");
        Set<String> publicPaths = Set.of("/api/auth/signup", "/api/auth/login", "/api/auth/logout",
                "/api/auth/reissue", "/api/auth/csrf");
        int count = 0;
        var paths = document.path("paths").fields();
        while (paths.hasNext()) {
            var path = paths.next();
            var methods = path.getValue().fields();
            while (methods.hasNext()) {
                var method = methods.next();
                if (!verbs.contains(method.getKey())) continue;
                count++;
                String context = method.getKey() + " " + path.getKey();
                JsonNode operation = method.getValue();
                if (!path.getKey().equals("/api/auth/csrf")) {
                    JsonNode unavailable = operation.path("responses").path("503").path("content").path("application/json");
                    assertTrue(unavailable.has("schema"), context);
                    assertEquals("AUTH_STORE_UNAVAILABLE", unavailable.path("example").path("error").path("code").asText(), context);
                }
                assertFalse(operation.path("summary").asText().isBlank(), context);
                assertFalse(operation.path("description").asText().isBlank(), context);
                assertFalse(operation.path("tags").isEmpty(), context);
                if (publicPaths.contains(path.getKey())) {
                    for (JsonNode requirement : operation.path("security")) {
                        assertFalse(requirement.has("BearerAuth"), context);
                        assertFalse(requirement.has("AccessCookie"), context);
                    }
                } else {
                    assertFalse(operation.path("security").toString().contains("BearerAuth"), context);
                    assertTrue(operation.path("security").toString().contains("AccessCookie"), context);
                    assertTrue(operation.path("responses").has("401"), context);
                }
                for (JsonNode requirement : operation.path("security")) {
                    assertFalse(requirement.has("RefreshCookie"), context);
                }
                if (!method.getKey().equals("get")) {
                    for (JsonNode parameter : operation.path("parameters")) {
                        assertNotEquals("X-XSRF-TOKEN", parameter.path("name").asText(), context);
                    }
                    assertTrue(operation.path("responses").has("403"), context);
                } else {
                    for (JsonNode requirement : operation.path("security")) {
                        assertFalse(requirement.has("CsrfToken"), context);
                    }
                }
                for (JsonNode media : operation.path("requestBody").path("content")) {
                    assertExample(media, context + " request");
                }
                var responses = operation.path("responses").fields();
                boolean success = false;
                while (responses.hasNext()) {
                    var response = responses.next();
                    if (response.getKey().startsWith("2") || response.getKey().startsWith("3")) {
                        success = true;
                        for (JsonNode media : response.getValue().path("content")) {
                            assertExample(media, context + " response " + response.getKey());
                            assertTrue(media.has("schema"), context);
                        }
                    }
                }
                assertTrue(success, context);
            }
        }
        assertEquals(20, count);
        JsonNode schemes = document.path("components").path("securitySchemes");
        assertFalse(schemes.has("CsrfToken"));
        assertFalse(schemes.has("BearerAuth"));
        assertFalse(schemes.has("RefreshCookie"));
        assertTrue(document.path("components").path("schemas").has("BusinessErrorResponse"));
        assertTrue(document.path("paths").path("/api/auth/signup").path("post")
                .path("responses").has("200"));
        assertFalse(document.path("paths").path("/api/auth/signup").path("post")
                .path("responses").has("302"));
        assertTrue(document.path("paths").path("/api/auth/reissue").path("post")
                .path("responses").has("400"));
        verifyLocalReferences(document, document);
        JsonNode userSchema = resolve(document, document.path("paths").path("/api/auth/me").path("get")
                .path("responses").path("200").path("content").path("application/json").path("schema"));
        JsonNode userData = resolve(document, userSchema.path("properties").path("data"));
        assertTrue(userData.path("properties").has("email"), "User response must retain generic data schema");
    }

    @Test
    void swaggerInitializerReadsCsrfCookieAndAddsHeader() throws Exception {
        String script = mvc.perform(get("/swagger-ui/swagger-initializer.js"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(script.contains("requestInterceptor"));
        assertTrue(script.contains("document.cookie"));
        assertTrue(script.contains("XSRF-TOKEN"));
        assertTrue(script.contains("X-XSRF-TOKEN"));
    }

    private JsonNode resolve(JsonNode document, JsonNode schema) {
        return schema.has("$ref") ? document.at(schema.path("$ref").asText().substring(1)) : schema;
    }

    private void verifyLocalReferences(JsonNode document, JsonNode node) {
        if (node.has("$ref") && node.path("$ref").asText().startsWith("#/")) {
            assertFalse(resolve(document, node).isMissingNode(), node.path("$ref").asText());
        }
        for (JsonNode child : node) verifyLocalReferences(document, child);
    }

    private void assertExample(JsonNode media, String context) throws Exception {
        assertTrue(media.has("example") || !media.path("examples").isEmpty(), context);
        // JSON examples must parse so they can be copied directly into a request.
        if (media.has("example") && media.get("example").isTextual()) {
            String example = media.get("example").asText();
            if (example.startsWith("{") || example.startsWith("[")) mapper.readTree(example);
        }
        for (JsonNode exampleNode : media.path("examples")) {
            String example = exampleNode.path("value").asText();
            if (example.startsWith("{") || example.startsWith("[")) mapper.readTree(example);
        }
    }
}
