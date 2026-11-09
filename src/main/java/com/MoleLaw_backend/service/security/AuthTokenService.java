package com.MoleLaw_backend.service.security;

import com.MoleLaw_backend.dto.security.RefreshTokenGrant;

import com.MoleLaw_backend.util.CookieUtil;

import com.MoleLaw_backend.dto.response.AuthResponse;
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
        cookies.clearJwtCookie(response, "accessToken");
        cookies.clearJwtCookie(response, "refreshToken");
    }

    private AuthResponse write(String access, RefreshTokenGrant refresh, HttpServletResponse response) {
        cookies.addRefreshCookie(response, refresh.value(), refresh.expiresAt());
        cookies.addJwtCookie(response, "accessToken", access);
        return new AuthResponse(access);
    }
}
