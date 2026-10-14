package com.MoleLaw_backend.auth.security;

import java.time.Duration;

import com.MoleLaw_backend.user.entity.User;
import com.MoleLaw_backend.user.repository.UserRepository;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefreshTokenValidationTest {
    private final JwtUtil jwt = new JwtUtil("test-secret-key-with-at-least-thirty-two-bytes", Duration.ofMinutes(5));
    private final CookieUtil cookies = mock(CookieUtil.class);
    private final UserRepository users = mock(UserRepository.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final RefreshTokenService refresh = new RefreshTokenService(new InMemoryRefreshTokenStore());
    private final UserService service = new UserService(users, mock(PasswordEncoder.class), cookies,
            new AuthTokenService(new AccessTokenService(jwt), refresh, cookies), refresh);

    @BeforeEach
    void setUp() {
        jwt.init();
    }

    @Test
    void rejectsAccessTokenInRefreshCookieBeforeUserLookup() {
        when(cookies.getTokenFromCookie(request, "refreshToken"))
                .thenReturn(jwt.generateAccessToken(1L));
        assertThatThrownBy(() -> service.reissue(request, response)).isInstanceOf(MolelawException.class);
        verifyNoInteractions(users);
    }

    @Test
    void rejectsDeletedUsersRefreshToken() {
        when(cookies.getTokenFromCookie(request, "refreshToken"))
                .thenReturn(refresh.issue(1L).value());
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reissue(request, response)).isInstanceOf(MolelawException.class);
        verify(cookies, never()).addJwtCookie(any(), anyString(), anyString());
    }

    @Test
    void validRefreshWorksWithoutAnAccessToken() {
        when(cookies.getTokenFromCookie(request, "refreshToken"))
                .thenReturn(refresh.issue(1L).value());
        when(users.findByIdForUpdate(1L))
                .thenReturn(Optional.of(User.builder().id(1L).email("u@example.com").provider("local").build()));
        assertThat(jwt.validateAccessToken(service.reissue(request, response).getAccessToken())).isTrue();
        verify(cookies).addJwtCookie(eq(response), eq("accessToken"), anyString());
        verify(cookies).addRefreshCookie(eq(response), anyString(), any());
    }

    @Test
    void rejectsLegacyRefreshJwtBeforeUserLookup() {
        when(cookies.getTokenFromCookie(request, "refreshToken")).thenReturn(TestJwtTokens.legacyRefresh(1L));
        assertThatThrownBy(() -> service.reissue(request, response)).isInstanceOf(MolelawException.class);
        verifyNoInteractions(users);
    }
}
