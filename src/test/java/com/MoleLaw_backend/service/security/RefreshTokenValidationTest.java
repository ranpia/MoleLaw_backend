package com.MoleLaw_backend.service.security;

import com.MoleLaw_backend.domain.entity.User;
import com.MoleLaw_backend.domain.repository.UserRepository;
import com.MoleLaw_backend.exception.MolelawException;
import com.MoleLaw_backend.service.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefreshTokenValidationTest {
    private final JwtUtil jwt = new JwtUtil("test-secret-key-with-at-least-thirty-two-bytes");
    private final CookieUtil cookies = mock(CookieUtil.class);
    private final UserRepository users = mock(UserRepository.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final UserService service = new UserService(users, mock(PasswordEncoder.class), jwt, cookies);

    @BeforeEach
    void setUp() {
        jwt.init();
    }

    @Test
    void rejectsAccessTokenInRefreshCookieBeforeUserLookup() {
        when(cookies.getTokenFromCookie(request, "refreshToken"))
                .thenReturn(jwt.generateAccessToken("u@example.com", "local"));
        assertThatThrownBy(() -> service.reissue(request, response)).isInstanceOf(MolelawException.class);
        verifyNoInteractions(users);
    }

    @Test
    void rejectsDeletedUsersRefreshToken() {
        when(cookies.getTokenFromCookie(request, "refreshToken"))
                .thenReturn(jwt.generateRefreshToken("u@example.com", "local"));
        when(users.findByEmailAndProvider("u@example.com", "local")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reissue(request, response)).isInstanceOf(MolelawException.class);
        verify(cookies, never()).addJwtCookie(any(), anyString(), anyString());
    }

    @Test
    void validRefreshWorksWithoutAnAccessToken() {
        when(cookies.getTokenFromCookie(request, "refreshToken"))
                .thenReturn(jwt.generateRefreshToken("u@example.com", "local"));
        when(users.findByEmailAndProvider("u@example.com", "local"))
                .thenReturn(Optional.of(User.builder().email("u@example.com").provider("local").build()));
        assertThat(jwt.validateAccessToken(service.reissue(request, response).getAccessToken())).isTrue();
        verify(cookies).addJwtCookie(eq(response), eq("accessToken"), anyString());
    }
}
