package com.MoleLaw_backend.auth.security;

import com.MoleLaw_backend.auth.dto.response.AuthResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthTokenService {
    private final AccessTokenService accessTokens;
    private final RefreshTokenService refreshTokens;
    private final CookieUtil cookies;
    private final JwtUtil jwt;

    public AuthResponse issue(Long userId, HttpServletResponse response) {
        String access = accessTokens.issue(userId);
        var refresh = refreshTokens.issue(userId);
        return write(access, refresh, response);
    }

    public AuthResponse rotate(String value, HttpServletResponse response) {
        var refresh = refreshTokens.rotate(value);
        String access = accessTokens.issue(refresh.userId());
        return write(access, refresh, response);
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        refreshTokens.revoke(cookies.getTokenFromCookie(request, "refreshToken"));
        String bearer = jwt.resolveToken(request);
        accessTokens.revoke(bearer);
        String cookie = cookies.getTokenFromCookie(request, "accessToken");
        if (!java.util.Objects.equals(bearer, cookie)) accessTokens.revoke(cookie);
        cookies.clearJwtCookie(response, "accessToken");
        cookies.clearJwtCookie(response, "refreshToken");
    }

    private AuthResponse write(String access, RefreshTokenService.Grant refresh, HttpServletResponse response) {
        cookies.addRefreshCookie(response, refresh.value(), refresh.expiresAt());
        cookies.addJwtCookie(response, "accessToken", access);
        return new AuthResponse(access);
    }
}
