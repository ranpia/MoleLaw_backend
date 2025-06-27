package com.MoleLaw_backend.service.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class CookieUtil {

    public void addJwtCookie(HttpServletResponse response, String name, String token, boolean secure) {
        ResponseCookie cookie = ResponseCookie.from(name, token)
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .sameSite("None") // SameSite=Lax 도 고려 가능
                .maxAge(86400) // 1일
                .build();

        response.addHeader("Set-Cookie", cookie.toString());
    }

    public void clearJwtCookie(HttpServletResponse response, String name, boolean secure) {
        ResponseCookie cookie = ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .sameSite("None")
                .maxAge(0)
                .build();

        response.addHeader("Set-Cookie", cookie.toString());
    }
}
