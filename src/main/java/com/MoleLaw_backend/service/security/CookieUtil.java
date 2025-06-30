package com.MoleLaw_backend.service.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class CookieUtil {

    public void addJwtCookie(HttpServletResponse response, String name, String token, boolean secure) {
        ResponseCookie cookie = ResponseCookie.from(name, token)
                .httpOnly(true)
                .secure(secure)  // ✅ 운영 서버에서 true
                .path("/")
                .sameSite("None")  // ✅ 크로스도메인 동작 위해 반드시 None
                .maxAge(86400)
                .build();

        response.addHeader("Set-Cookie", cookie.toString());

        System.out.println("🍪 [쿠키 저장] name: " + name + " | secure=" + secure);
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
