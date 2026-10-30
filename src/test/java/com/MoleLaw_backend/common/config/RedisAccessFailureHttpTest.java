package com.MoleLaw_backend.common.config;

import com.MoleLaw_backend.user.entity.User;
import com.MoleLaw_backend.user.repository.UserRepository;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.auth.security.JwtUtil;
import com.MoleLaw_backend.auth.security.RefreshTokenStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    @MockitoBean RefreshTokenStore refreshStore;
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;

    @Test
    void redisOutageDoesNotAffectAccessButPreventsLoginRotationAndLogout() throws Exception {
        User user = users.save(User.builder().email("stateless-outage@example.com").provider("local")
                .nickname("stateless").password(passwords.encode("Example-password-123!")).build());
        when(refreshStore.generation(anyLong())).thenThrow(unavailable());
        when(refreshStore.rotate(anyLong(), anyString(), anyString(), anyString())).thenThrow(unavailable());
        doThrow(unavailable()).when(refreshStore).revoke(anyLong(), anyString(), anyString());
        String token = jwt.generateAccessToken(user.getId());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("data.email").value(user.getEmail()));
        mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk());
        verifyNoInteractions(refreshStore);
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
                        .content("{\"email\":\"stateless-outage@example.com\",\"password\":\"Example-password-123!\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist("Set-Cookie"));
        String refresh = "r1." + user.getId() + ".00000000-0000-0000-0000-000000000001." + "a".repeat(43);
        mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(new Cookie("refreshToken", refresh)))
                .andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(post("/api/auth/logout").with(csrf()).cookie(new Cookie("refreshToken", refresh)))
                .andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get("/api/users/me").cookie(new Cookie("accessToken", token))).andExpect(status().isOk());
    }

    @Test
    void failedSignupRefreshPersistenceReturnsNoTokenAndRollsBackAccount() throws Exception {
        when(refreshStore.generation(anyLong())).thenThrow(unavailable());
        mvc.perform(post("/api/auth/signup").with(csrf()).contentType("application/json")
                        .content("{\"email\":\"redis-failure@example.com\",\"password\":\"Example-password-123!\",\"nickname\":\"redis-test\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("error.code").value("AUTH_STORE_UNAVAILABLE"));
        assertThat(users.existsByEmailAndProvider("redis-failure@example.com", "local")).isFalse();
    }

    private MolelawException unavailable() {
        return new MolelawException(ErrorCode.AUTH_STORE_UNAVAILABLE);
    }
}