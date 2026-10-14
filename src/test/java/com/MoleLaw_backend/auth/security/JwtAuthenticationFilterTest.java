package com.MoleLaw_backend.auth.security;

import java.time.Duration;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {
    private final JwtUtil jwtUtil = new JwtUtil("test-secret-key-with-at-least-thirty-two-bytes", Duration.ofMinutes(5));
    private final CustomUserDetailsService users = mock(CustomUserDetailsService.class);
    private final AccessTokenStore store = mock(AccessTokenStore.class);
    private final AccessTokenService accessTokens = new AccessTokenService(jwtUtil, store);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void refreshBearerNeverAuthenticatesOrLoadsAUser() throws Exception {
        jwtUtil.init();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("Authorization", "Bearer " + jwtUtil.generateRefreshToken("u@example.com", "local"));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new JwtAuthenticationFilter(jwtUtil, users, accessTokens).doFilter(request, response, chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(users);
        verify(chain).doFilter(request, response);
    }

    @Test
    void deletedUserAccessNeverAuthenticates() throws Exception {
        jwtUtil.init();
        when(users.loadUserByUsername("u@example.com:local"))
                .thenThrow(new UsernameNotFoundException("missing"));
        when(store.isActive(anyString(), anyString())).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("Authorization", "Bearer " + jwtUtil.generateAccessToken("u@example.com", "local"));
        new JwtAuthenticationFilter(jwtUtil, users, accessTokens)
                .doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(users).loadUserByUsername("u@example.com:local");
    }

    @Test
    void validButUnregisteredAccessNeverAuthenticatesOrRecreatesAKey() throws Exception {
        jwtUtil.init();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("Authorization", "Bearer " + jwtUtil.generateAccessToken("u@example.com", "local"));
        new JwtAuthenticationFilter(jwtUtil, users, accessTokens)
                .doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(store).isActive(anyString(), eq("u@example.com:local"));
        verify(store, never()).register(anyString(), anyString(), any());
        verifyNoInteractions(users);
    }

    @Test
    void redisFailureReturns503WithoutAuthenticatingOrCallingTheController() throws Exception {
        jwtUtil.init();
        when(store.isActive(anyString(), anyString())).thenThrow(new com.MoleLaw_backend.common.exception.MolelawException(
                com.MoleLaw_backend.common.exception.ErrorCode.AUTH_STORE_UNAVAILABLE));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("Authorization", "Bearer " + jwtUtil.generateAccessToken("u@example.com", "local"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        new JwtAuthenticationFilter(jwtUtil, users, accessTokens).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("AUTH_STORE_UNAVAILABLE");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(users, chain);
    }
}
