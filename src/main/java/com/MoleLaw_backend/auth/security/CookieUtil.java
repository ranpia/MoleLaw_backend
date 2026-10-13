package com.MoleLaw_backend.auth.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class CookieUtil {
    private final boolean secure;
    private final String sameSite;

    public CookieUtil(@Value("${cookie.secure:true}") boolean secure,
                      @Value("${cookie.same-site:None}") String sameSite,
                      Environment environment) {
        if (!secure && !environment.acceptsProfiles(Profiles.of("local", "test"))) {
            throw new IllegalArgumentException("Insecure cookies require the local or test profile");
        }
        if (!java.util.Set.of("None", "Lax", "Strict").contains(sameSite)
                || (!secure && "None".equals(sameSite))) {
            throw new IllegalArgumentException("Invalid SameSite/Secure cookie policy");
        }
        this.secure = secure;
        this.sameSite = sameSite;
    }

    public void addJwtCookie(HttpServletResponse response, String name, String token) {
        long maxAge = switch (name) {
            case "accessToken" -> 15 * 60 * 60;
            case "refreshToken" -> 7 * 24 * 60 * 60;
            default -> throw new IllegalArgumentException("Unknown authentication cookie");
        };
        writeCookie(response, name, token, maxAge);
        clearLegacyRefreshCookie(response, name);
    }

    public void clearJwtCookie(HttpServletResponse response, String name) {
        writeCookie(response, name, "", 0);
        clearLegacyRefreshCookie(response, name);
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
