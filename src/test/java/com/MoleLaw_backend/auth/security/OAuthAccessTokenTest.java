package com.MoleLaw_backend.auth.security;

import java.time.Duration;

import com.MoleLaw_backend.user.entity.User;
import com.MoleLaw_backend.user.repository.UserRepository;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.auth.oauth.OAuth2SuccessHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OAuthAccessTokenTest {
    @Test
    void newOAuthAccountUsesTheGeneratedPrimaryKey() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.users.findByCredentialsForUpdate("oauth@example.com", "google")).thenReturn(Optional.empty());
        when(fixture.users.save(any(User.class))).thenReturn(User.builder().id(42L)
                .email("oauth@example.com").provider("google").build());
        fixture.handler.onAuthenticationSuccess(new MockHttpServletRequest(), fixture.response, fixture.authentication);
        var access = ArgumentCaptor.forClass(String.class);
        var refresh = ArgumentCaptor.forClass(String.class);
        verify(fixture.cookies).addJwtCookie(eq(fixture.response), eq("accessToken"), access.capture());
        verify(fixture.cookies).addRefreshCookie(eq(fixture.response), refresh.capture(), any());
        assertThat(fixture.jwt.getUserIdFromAccessToken(access.getValue())).isEqualTo(42L);
        assertThat(fixture.refresh.userId(refresh.getValue())).isEqualTo(42L);
    }

    @Test
    void oauthRegistersBeforeSettingCookiesAndRedirecting() throws Exception {
        Fixture fixture = new Fixture();
        fixture.handler.onAuthenticationSuccess(new MockHttpServletRequest(), fixture.response, fixture.authentication);
        var token = ArgumentCaptor.forClass(String.class);
        verify(fixture.cookies).addJwtCookie(eq(fixture.response), eq("accessToken"), token.capture());
        var identity = fixture.jwt.getAccessTokenIdentity(token.getValue());
        verify(fixture.store).register(identity.tokenId(), "1", identity.expiresAt());
        assertThat(fixture.response.getRedirectedUrl()).isEqualTo("http://localhost:3000");
    }

    @Test
    void oauthRegistrationFailureReturns503WithoutCookiesOrRedirect() throws Exception {
        Fixture fixture = new Fixture();
        doThrow(new MolelawException(ErrorCode.AUTH_STORE_UNAVAILABLE))
                .when(fixture.store).register(anyString(), anyString(), any());
        fixture.handler.onAuthenticationSuccess(new MockHttpServletRequest(), fixture.response, fixture.authentication);
        assertThat(fixture.response.getStatus()).isEqualTo(503);
        assertThat(fixture.response.getContentAsString()).contains("AUTH_STORE_UNAVAILABLE");
        assertThat(fixture.response.getRedirectedUrl()).isNull();
        verifyNoInteractions(fixture.cookies);
    }

    private static class Fixture {
        final JwtUtil jwt = new JwtUtil("test-secret-key-with-at-least-thirty-two-bytes", Duration.ofMinutes(5));
        final AccessTokenStore store = mock(AccessTokenStore.class);
        final CookieUtil cookies = mock(CookieUtil.class);
        final UserRepository users = mock(UserRepository.class);
        final Authentication authentication = mock(Authentication.class);
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final RefreshTokenService refresh = new RefreshTokenService(new InMemoryRefreshTokenStore());
        final OAuth2SuccessHandler handler = new OAuth2SuccessHandler(users,
                new AuthTokenService(new AccessTokenService(jwt, store), refresh, cookies, jwt));

        Fixture() {
            jwt.init();
            ReflectionTestUtils.setField(handler, "frontenduri", "http://localhost:3000");
            when(authentication.getPrincipal()).thenReturn(new DefaultOAuth2User(
                    List.of(new SimpleGrantedAuthority("ROLE_USER")),
                    Map.of("email", "oauth@example.com", "provider", "google"), "email"));
            when(users.findByCredentialsForUpdate("oauth@example.com", "google"))
                    .thenReturn(Optional.of(User.builder().id(1L).email("oauth@example.com").provider("google").build()));
        }
    }
}
