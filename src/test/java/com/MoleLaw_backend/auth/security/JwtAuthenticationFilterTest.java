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
    private final org.springframework.security.web.context.RequestAttributeSecurityContextRepository contexts =
            new org.springframework.security.web.context.RequestAttributeSecurityContextRepository();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void refreshBearerNeverAuthenticatesOrLoadsAUser() throws Exception {
        jwtUtil.init();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        request.addHeader("Authorization", "Bearer " + TestJwtTokens.legacyRefresh(1L));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new JwtAuthenticationFilter(jwtUtil, users, contexts).doFilter(request, response, chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(users);
        verify(chain).doFilter(request, response);
    }

    @Test
    void deletedUserAccessNeverAuthenticates() throws Exception {
        jwtUtil.init();
        when(users.loadUserByUsername("1"))
                .thenThrow(new UsernameNotFoundException("missing"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        request.addHeader("Authorization", "Bearer " + jwtUtil.generateAccessToken(1L));
        new JwtAuthenticationFilter(jwtUtil, users, contexts)
                .doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(users).loadUserByUsername("1");
    }

    @Test
    void savesCookieAuthenticationForRedispatchWithoutCreatingASessionOrAuthenticatingAnotherRequest() throws Exception {
        jwtUtil.init();
        var account = mock(com.MoleLaw_backend.user.entity.User.class);
        when(account.getId()).thenReturn(1L);
        when(users.loadUserByUsername("1")).thenReturn(new CustomUserDetails(account));
        var request = new MockHttpServletRequest("POST", "/api/legal-answers/ndjson");
        request.setCookies(new jakarta.servlet.http.Cookie("accessToken", jwtUtil.generateAccessToken(1L)));
        var response = new MockHttpServletResponse();
        new JwtAuthenticationFilter(jwtUtil, users, contexts).doFilter(request, response, mock(FilterChain.class));
        assertThat(contexts.containsContext(request)).isTrue();
        SecurityContextHolder.clearContext();
        response.flushBuffer();
        request.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC);
        assertThat(contexts.loadDeferredContext(request).get().getAuthentication().getName()).isEqualTo("1");
        assertThat(request.getSession(false)).isNull();
        assertThat(contexts.loadDeferredContext(new MockHttpServletRequest()).get().getAuthentication()).isNull();
    }

}
