package com.MoleLaw_backend.service.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
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
                .sameSite("None")
                .maxAge(60 * 60 * 24) // 1일
                .build();

        response.addHeader("Set-Cookie", cookie.toString());
        System.out.println("🍪 [쿠키 저장] name=" + name + " | secure=" + secure);
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
        System.out.println("🍪 [쿠키 삭제] name=" + name + " | secure=" + secure);
    }

    public String getTokenFromCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (cookie.getName().equals(name)) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
