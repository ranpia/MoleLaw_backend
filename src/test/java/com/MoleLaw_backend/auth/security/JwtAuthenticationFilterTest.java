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

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void refreshBearerNeverAuthenticatesOrLoadsAUser() throws Exception {
        jwtUtil.init();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("Authorization", "Bearer " + TestJwtTokens.legacyRefresh(1L));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new JwtAuthenticationFilter(jwtUtil, users).doFilter(request, response, chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(users);
        verify(chain).doFilter(request, response);
    }

    @Test
    void deletedUserAccessNeverAuthenticates() throws Exception {
        jwtUtil.init();
        when(users.loadUserByUsername("1"))
                .thenThrow(new UsernameNotFoundException("missing"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("Authorization", "Bearer " + jwtUtil.generateAccessToken(1L));
        new JwtAuthenticationFilter(jwtUtil, users)
                .doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(users).loadUserByUsername("1");
    }

}
