package com.MoleLaw_backend.common.config;

import com.MoleLaw_backend.auth.security.AllowedOriginFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.MoleLaw_backend.common.config.TestAccessTokenStoreConfig.class)
@ActiveProfiles("test")
class BrowserSecurityTest {
    @MockitoBean ChatModel chatModel;
    @MockitoBean EmbeddingModel embeddingModel;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired com.MoleLaw_backend.auth.security.AccessTokenStore accessTokenStore;
    @Autowired com.MoleLaw_backend.auth.security.JwtUtil jwtUtil;

    @Test
    void logoutRevokesBothPresentedAccessTokensAndCopiedRefreshRemainsASeparateFollowup() throws Exception {
        var bootstrap = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
        Cookie csrfCookie = bootstrap.getCookie("XSRF-TOKEN");
        String csrf = mapper.readTree(bootstrap.getContentAsString()).get("token").asText();
        var signup = mvc.perform(post("/api/auth/signup").cookie(csrfCookie).header("X-XSRF-TOKEN", csrf)
                        .contentType("application/json")
                        .content("{\"email\":\"redis-logout@example.com\",\"password\":\"Example-password-123!\",\"nickname\":\"redis-test\"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        Cookie firstAccess = signup.getCookie("accessToken");
        // Refresh has a scoped cookie plus legacy cleanup cookie; select the live scoped one.
        Cookie refresh = java.util.Arrays.stream(signup.getCookies())
                .filter(cookie -> cookie.getName().equals("refreshToken") && cookie.getPath().equals("/api/auth"))
                .findFirst().orElseThrow();
        var login = mvc.perform(post("/api/auth/login").cookie(csrfCookie).header("X-XSRF-TOKEN", csrf)
                        .contentType("application/json")
                        .content("{\"email\":\"redis-logout@example.com\",\"password\":\"Example-password-123!\"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        Cookie secondAccess = login.getCookie("accessToken");
        mvc.perform(get("/api/auth/me").cookie(firstAccess)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + secondAccess.getValue()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").cookie(csrfCookie, firstAccess)
                        .header("X-XSRF-TOKEN", csrf).header("Authorization", "Bearer " + secondAccess.getValue()))
                .andExpect(status().isOk()).andExpect(jsonPath("success").value(true));
        mvc.perform(get("/api/auth/me").cookie(firstAccess)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(secondAccess)).andExpect(status().isUnauthorized());
        var reissue = mvc.perform(post("/api/auth/reissue").cookie(csrfCookie, refresh, firstAccess)
                        .header("X-XSRF-TOKEN", csrf))
                .andExpect(status().isOk()).andReturn().getResponse();
        mvc.perform(get("/api/auth/me").cookie(reissue.getCookie("accessToken"))).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").cookie(firstAccess)).andExpect(status().isUnauthorized());
    }

    @Test
    void removingRedisRegistrationMakesAValidSignedAccessUnauthorized() throws Exception {
        var bootstrap = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
        String csrf = mapper.readTree(bootstrap.getContentAsString()).get("token").asText();
        var signup = mvc.perform(post("/api/auth/signup").cookie(bootstrap.getCookie("XSRF-TOKEN"))
                        .header("X-XSRF-TOKEN", csrf).contentType("application/json")
                        .content("{\"email\":\"redis-missing@example.com\",\"password\":\"Example-password-123!\",\"nickname\":\"redis-test\"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        Cookie access = signup.getCookie("accessToken");
        mvc.perform(get("/api/auth/me").cookie(access)).andExpect(status().isOk());
        String id = jwtUtil.getAccessTokenIdentity(access.getValue()).tokenId();
        accessTokenStore.revoke(id);
        mvc.perform(get("/api/auth/me").cookie(access)).andExpect(status().isUnauthorized());
        assertTrue(jwtUtil.validateAccessToken(access.getValue()));
        assertFalse(accessTokenStore.isActive(id, "redis-missing@example.com:local"));
    }

    @Test
    void csrfBootstrapIsPublicAndReturnsReadableCookieForSwagger() throws Exception {
        var response = mvc.perform(get("/api/auth/csrf").header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("headerName").value("X-XSRF-TOKEN"))
                .andReturn().getResponse();
        Cookie cookie = response.getCookie("XSRF-TOKEN");
        assertNotNull(cookie);
        assertFalse(cookie.isHttpOnly());
        assertEquals("/", cookie.getPath());
    }

    @Test
    void allPublicAuthMutationsRequireCsrfEvenWithoutAuthentication() throws Exception {
        for (String endpoint : new String[]{"login", "signup", "logout", "reissue"}) {
            mvc.perform(post("/api/auth/" + endpoint).header("Origin", "http://localhost:3000"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void signupCompletesWithoutRedirectingToProtectedBackendPage() throws Exception {
        var bootstrap = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
        String csrf = mapper.readTree(bootstrap.getContentAsString()).get("token").asText();
        var signup = mvc.perform(post("/api/auth/signup")
                        .cookie(bootstrap.getCookie("XSRF-TOKEN"))
                        .header("X-XSRF-TOKEN", csrf)
                        .header("Origin", "http://localhost:3000")
                        .contentType("application/json")
                        .content("{\"email\":\"signup-regression@example.com\",\"password\":\"Example-password-123!\",\"nickname\":\"signup-test\"}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("success").value(true))
                .andReturn().getResponse();
        Cookie access = signup.getCookie("accessToken");
        assertNotNull(access);
        mvc.perform(get("/api/auth/me").cookie(access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.email").value("signup-regression@example.com"));
    }

    @Test
    void actualBootstrapTokenAllowsLogoutButForgedOrMissingTokenDoesNot() throws Exception {
        var response = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
        Cookie cookie = response.getCookie("XSRF-TOKEN");
        String token = mapper.readTree(response.getContentAsString()).get("token").asText();
        mvc.perform(post("/api/auth/logout").cookie(cookie).header("X-XSRF-TOKEN", token)
                        .header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").cookie(cookie).header("X-XSRF-TOKEN", "forged"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").cookie(cookie))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").cookie(cookie).header("X-XSRF-TOKEN", token))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").cookie(cookie).header("X-XSRF-TOKEN", token)
                        .header("Origin", "https://attacker.example"))
                .andExpect(status().isForbidden());
    }

    @Test
    void preflightAllowsOnlyConfiguredOrigin() throws Exception {
        mvc.perform(options("/api/auth/login").header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-xsrf-token"))
                .andExpect(status().isOk());
        mvc.perform(options("/api/auth/login").header("Origin", "https://attacker.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void originConfigurationRejectsPatternsAndNonOrigins() {
        for (String invalid : new String[]{"*", "https://*.example.com", "null", "https://example.com/path", ""}) {
            assertThrows(IllegalArgumentException.class, () -> AllowedOriginFilter.parseOrigins(invalid));
        }
        assertEquals(java.util.List.of("https://example.com", "http://localhost:3000"),
                AllowedOriginFilter.parseOrigins("https://example.com/,http://localhost:3000"));
    }
}
