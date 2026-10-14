package com.MoleLaw_backend.auth.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class CookieUtil {
    private final boolean secure;
    private final String sameSite;
    private final Duration accessTtl;

    public CookieUtil(@Value("${cookie.secure:true}") boolean secure,
                      @Value("${cookie.same-site:None}") String sameSite,
                      Environment environment,
                      @Value("${jwt.access-ttl:5m}") Duration accessTtl) {
        if (!secure && !environment.acceptsProfiles(Profiles.of("local", "test"))) {
            throw new IllegalArgumentException("Insecure cookies require the local or test profile");
        }
        if (!java.util.Set.of("None", "Lax", "Strict").contains(sameSite)
                || (!secure && "None".equals(sameSite))) {
            throw new IllegalArgumentException("Invalid SameSite/Secure cookie policy");
        }
        this.secure = secure;
        this.sameSite = sameSite;
        if (accessTtl == null || accessTtl.isNegative() || accessTtl.isZero()
                || accessTtl.getNano() != 0 || accessTtl.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Access token TTL must be positive whole seconds up to 300 seconds");
        }
        this.accessTtl = accessTtl;
    }

    public void addJwtCookie(HttpServletResponse response, String name, String token) {
        long maxAge = switch (name) {
            case "accessToken" -> accessTtl.toSeconds();
            default -> throw new IllegalArgumentException("Unknown authentication cookie");
        };
        writeCookie(response, name, token, maxAge);
        clearLegacyRefreshCookie(response, name);
    }

    public void clearJwtCookie(HttpServletResponse response, String name) {
        writeCookie(response, name, "", 0);
        clearLegacyRefreshCookie(response, name);
    }

    public void addRefreshCookie(HttpServletResponse response, String value, Instant expiresAt) {
        long maxAge = Duration.between(Instant.now(), expiresAt).toSeconds();
        if (maxAge <= 0) throw new com.MoleLaw_backend.common.exception.MolelawException(
                com.MoleLaw_backend.common.exception.ErrorCode.INVALID_REFRESH_TOKEN);
        writeCookie(response, "refreshToken", value, maxAge);
        clearLegacyRefreshCookie(response, "refreshToken");
    }

    private void clearLegacyRefreshCookie(HttpServletResponse response, String name) {
        if ("refreshToken".equals(name)) {
            response.addHeader("Set-Cookie", buildCookie(name, "", "/", 0).toString());
        }
    }

    private void writeCookie(HttpServletResponse response, String name, String value, long maxAge) {
        String path = switch (name) {
            case "accessToken" -> "/";
            case "refreshToken" -> "/api/auth";
            default -> throw new IllegalArgumentException("Unknown authentication cookie");
        };
        response.addHeader("Set-Cookie", buildCookie(name, value, path, maxAge).toString());
    }

    private ResponseCookie buildCookie(String name, String value, String path, long maxAge) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(secure)
                .path(path).sameSite(sameSite).maxAge(maxAge).build();
    }

    public String getTokenFromCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (cookie.getName().equals(name)) return cookie.getValue();
        }
        return null;
    }
}
