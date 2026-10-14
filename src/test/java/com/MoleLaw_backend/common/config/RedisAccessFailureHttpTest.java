package com.MoleLaw_backend.common.config;

import com.MoleLaw_backend.user.repository.UserRepository;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.auth.security.AccessTokenStore;
import com.MoleLaw_backend.auth.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RedisAccessFailureHttpTest {
    @MockitoBean ChatModel chatModel;
    @MockitoBean EmbeddingModel embeddingModel;
    @MockitoBean AccessTokenStore store;
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @Autowired UserRepository users;

    @Test
    void redisReadFailureIs503RatherThan401AndBootstrapStillWorks() throws Exception {
        when(store.isActive(anyString(), anyString())).thenThrow(unavailable());
        String token = jwt.generateAccessToken(1L);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("error.code").value("AUTH_STORE_UNAVAILABLE"));
        mvc.perform(get("/api/auth/csrf").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("token").exists());
        verify(store, never()).register(anyString(), anyString(), any());
    }

    @Test
    void failedSignupRegistrationReturnsNoJwtAndRollsBackTheAccount() throws Exception {
        doThrow(unavailable()).when(store).register(anyString(), anyString(), any());
        mvc.perform(post("/api/auth/signup").with(csrf()).contentType("application/json")
                        .content("{\"email\":\"redis-failure@example.com\",\"password\":\"Example-password-123!\",\"nickname\":\"redis-test\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("error.code").value("AUTH_STORE_UNAVAILABLE"))
                .andExpect(jsonPath("data").doesNotExist());
        assertThat(users.existsByEmailAndProvider("redis-failure@example.com", "local")).isFalse();
    }

    @Test
    void failedLogoutRevocationDoesNotReportSuccessOrClearCookies() throws Exception {
        doThrow(unavailable()).when(store).revoke(anyString());
        mvc.perform(post("/api/auth/logout").with(csrf())
                        .header("Authorization", "Bearer " + jwt.generateAccessToken(1L)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("error.code").value("AUTH_STORE_UNAVAILABLE"));
    }

    private MolelawException unavailable() {
        return new MolelawException(ErrorCode.AUTH_STORE_UNAVAILABLE);
    }
}
