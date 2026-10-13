package com.MoleLaw_backend.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false",
        "springdoc.swagger-ui.csrf.enabled=false", "cookie.csrf-http-only=true"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.MoleLaw_backend.common.config.TestAccessTokenStoreConfig.class)
@ActiveProfiles("test")
class SwaggerDisabledTest {
    @MockitoBean ChatModel chatModel;
    @MockitoBean EmbeddingModel embeddingModel;
    @Autowired MockMvc mvc;

    @Test
    void productionDefaultsKeepDocumentationDisabledAndCsrfCookieHttpOnly() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var defaults = yaml.getObject();
        assertNotNull(defaults);
        assertEquals("false", defaults.getProperty("springdoc.api-docs.enabled"));
        assertEquals("false", defaults.getProperty("springdoc.swagger-ui.enabled"));
        assertEquals("false", defaults.getProperty("springdoc.swagger-ui.csrf.enabled"));
        assertEquals("true", defaults.getProperty("cookie.csrf-http-only"));
        // application.properties takes precedence over YAML and must not re-enable docs.
        var properties = new java.util.Properties();
        try (var stream = new ClassPathResource("application.properties").getInputStream()) {
            properties.load(stream);
        }
        assertNotEquals("true", properties.getProperty("springdoc.api-docs.enabled"));
        assertNotEquals("true", properties.getProperty("springdoc.swagger-ui.enabled"));
        for (String path : new String[]{"/v3/api-docs", "/swagger-ui.html", "/swagger-ui/index.html"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("test-user")))
                    .andExpect(status().isForbidden());
        }
        var response = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk())
                .andReturn().getResponse();
        assertNotNull(response.getCookie("XSRF-TOKEN"));
        assertTrue(response.getCookie("XSRF-TOKEN").isHttpOnly());
    }
}
