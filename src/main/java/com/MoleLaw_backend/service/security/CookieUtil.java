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
                .sameSite("Lax") // 브라우저 보안 테스트 시 Lax로 바꿔도 OK
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
                .sameSite("Lax")
                .maxAge(0)
                .build();

        response.addHeader("Set-Cookie", cookie.toString());
    }
}
