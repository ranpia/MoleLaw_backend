package com.MoleLaw_backend.common.config;

import com.MoleLaw_backend.auth.security.RefreshTokenStore;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestAccessTokenStoreConfig.class)
class RefreshRotationHttpTest {
    @MockitoBean ChatModel chatModel;
    @MockitoBean EmbeddingModel embeddingModel;
    @MockitoSpyBean RefreshTokenStore store;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired org.springframework.core.env.Environment environment;
    @Autowired com.MoleLaw_backend.auth.security.JwtUtil jwt;

    @Test
    void rotatesCookiesKeepsRefreshOutOfJsonAndRevokesFamilyOnReuse() throws Exception {
        var signup = signup("refresh-rotation@example.com");
        Cookie first = refresh(signup);
        var rotated = reissue(first);
        Cookie second = refresh(rotated);
        assertThat(first.getValue().equals(second.getValue())).isFalse();
        assertThat(second.isHttpOnly()).isTrue();
        assertThat(second.getMaxAge()).isLessThanOrEqualTo(first.getMaxAge());
        var body = mapper.readTree(rotated.getContentAsString());
        assertThat(body.path("data").has("accessToken")).isTrue();
        assertThat(body.path("data").has("refreshToken")).isFalse();
        var next = reissue(second);
        mvc.perform(post("/api/auth/reissue").with(csrf()).cookie(first))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("error.code").value("REFRESH_TOKEN_REUSED"));
        mvc.perform(post("/api/auth/reissue").with(csrf()).cookie(refresh(next)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("error.code").value("INVALID_REFRESH_TOKEN"));
        // This step revokes Refresh only; already issued Access follows its existing expiry policy.
        mvc.perform(get("/api/auth/me").cookie(next.getCookie("accessToken"))).andExpect(status().isOk());
    }

    @Test
    void logoutWithConsumedCookieRevokesTheLatestRefreshButNotAnotherLogin() throws Exception {
        var first = signup("refresh-logout@example.com");
        var next = reissue(refresh(first));
        var another = login("refresh-logout@example.com");
        assertThat(mapper.readTree(another.getContentAsString()).has("refreshToken")).isFalse();
        mvc.perform(post("/api/auth/logout").with(csrf()).cookie(refresh(first), next.getCookie("accessToken")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/reissue").with(csrf()).cookie(refresh(next)))
                .andExpect(status().isUnauthorized());
        reissue(refresh(another));
    }

    @Test
    void passwordChangeRevokesEveryRefreshFamilyAndLeavesIssuedAccessUntilExpiry() throws Exception {
        var first = signup("refresh-password@example.com");
        var second = login("refresh-password@example.com");
        mvc.perform(patch("/api/auth/password").with(csrf()).cookie(first.getCookie("accessToken"))
                        .contentType("application/json").content("{\"newPassword\":\"Changed-password-123!\"}"))
                .andExpect(status().isOk());
        for (var session : new MockHttpServletResponse[]{first, second}) {
            mvc.perform(post("/api/auth/reissue").with(csrf()).cookie(refresh(session)))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/auth/me").cookie(second.getCookie("accessToken"))).andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
                        .content("{\"email\":\"refresh-password@example.com\",\"password\":\"Changed-password-123!\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("refreshToken").doesNotExist());
    }

    @Test
    void failedUserWideRevocationRollsBackPasswordAndAccountDeletion() throws Exception {
        var signup = signup("refresh-failure@example.com");
        var user = users.findByEmailAndProvider("refresh-failure@example.com", "local").orElseThrow();
        doThrow(new MolelawException(ErrorCode.AUTH_STORE_UNAVAILABLE)).when(store).revokeAll(user.getId());
        mvc.perform(patch("/api/auth/password").with(csrf()).cookie(signup.getCookie("accessToken"))
                        .contentType("application/json").content("{\"newPassword\":\"Should-not-commit!\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("error.code").value("AUTH_STORE_UNAVAILABLE"));
        var unchanged = users.findById(user.getId()).orElseThrow();
        assertThat(passwords.matches("Example-password-123!", unchanged.getPassword())).isTrue();
        mvc.perform(delete("/api/auth/me").with(csrf()).cookie(signup.getCookie("accessToken")))
                .andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(users.existsById(user.getId())).isTrue();
        reissue(refresh(signup));
    }

    @Test
    void registrationFailureDoesNotReturnTokensOrCommitSignup() throws Exception {
        doThrow(new MolelawException(ErrorCode.AUTH_STORE_UNAVAILABLE)).when(store)
                .create(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
        mvc.perform(post("/api/auth/signup").with(csrf()).contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("email", "refresh-signup-failure@example.com",
                                "password", "Example-password-123!", "nickname", "검사"))))
                .andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("data").doesNotExist());
        assertThat(users.existsByEmailAndProvider("refresh-signup-failure@example.com", "local")).isFalse();
    }

    @Test
    void legacyMissingAndUnknownRefreshCookiesAre401() throws Exception {
        mvc.perform(post("/api/auth/reissue").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/reissue").with(csrf()).cookie(new Cookie("refreshToken", "legacy.jwt.value")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("error.code").value("INVALID_REFRESH_TOKEN"));
        var signup = signup("refresh-unknown@example.com");
        String value = refresh(signup).getValue();
        String fake = value.substring(0, value.lastIndexOf('.') + 1) + "A".repeat(43);
        mvc.perform(post("/api/auth/reissue").with(csrf()).cookie(new Cookie("refreshToken", fake)))
                .andExpect(status().isUnauthorized());
        reissue(refresh(signup));
    }

    @Test
    void refreshRotationWorksWithAnExpiredPreviousAccessCookie() throws Exception {
        var signup = signup("refresh-expired-access@example.com");
        String expired = io.jsonwebtoken.Jwts.builder()
                .setSubject(jwt.getUserIdFromAccessToken(signup.getCookie("accessToken").getValue()).toString())
                .claim("tokenType", "access").claim("tokenVersion", 2).setId("expired-access-test")
                .setIssuedAt(new java.util.Date(System.currentTimeMillis() - 120000))
                .setExpiration(new java.util.Date(System.currentTimeMillis() - 1000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(environment.getRequiredProperty("jwt.secret")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)), io.jsonwebtoken.SignatureAlgorithm.HS256).compact();
        assertThat(jwt.validateAccessToken(expired)).isFalse();
        mvc.perform(post("/api/auth/reissue").with(csrf()).cookie(refresh(signup), new Cookie("accessToken", expired)))
                .andExpect(status().isOk()).andExpect(jsonPath("data.accessToken").exists())
                .andExpect(jsonPath("data.refreshToken").doesNotExist());
    }

    private MockHttpServletResponse signup(String email) throws Exception {
        return mvc.perform(post("/api/auth/signup").with(csrf()).contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "Example-password-123!", "nickname", "회전 검사"))))
                .andExpect(status().isOk()).andReturn().getResponse();
    }

    private MockHttpServletResponse login(String email) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "Example-password-123!"))))
                .andExpect(status().isOk()).andReturn().getResponse();
    }

    private MockHttpServletResponse reissue(Cookie refresh) throws Exception {
        return mvc.perform(post("/api/auth/reissue").with(csrf()).cookie(refresh))
                .andExpect(status().isOk()).andReturn().getResponse();
    }

    private Cookie refresh(MockHttpServletResponse response) {
        return Arrays.stream(response.getCookies()).filter(cookie -> cookie.getName().equals("refreshToken")
                && "/api/auth".equals(cookie.getPath()) && cookie.getMaxAge() > 0).findFirst().orElseThrow();
    }
}
