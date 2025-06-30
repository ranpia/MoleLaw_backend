package com.MoleLaw_backend.service.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class CookieUtil {

    /**
     * JWT 쿠키 저장
     * @param response HttpServletResponse
     * @param name 쿠키 이름 (예: "accessToken", "refreshToken")
     * @param token JWT 문자열
     * @param secure HTTPS 환경 여부
     */
    public void addJwtCookie(HttpServletResponse response, String name, String token, boolean secure) {
        ResponseCookie cookie = ResponseCookie.from(name, token)
                .httpOnly(true)
                .secure(secure) // HTTPS 전용 여부
                .path("/") // 전체 경로에 대해 쿠키 적용
                .sameSite("None") // 크로스도메인 쿠키 허용
                .maxAge(60 * 60 * 24) // 1일
                .build();

        response.addHeader("Set-Cookie", cookie.toString());

        System.out.println("🍪 [쿠키 저장] name=" + name + " | secure=" + secure);
    }

    /**
     * JWT 쿠키 삭제
     * @param response HttpServletResponse
     * @param name 쿠키 이름
     * @param secure HTTPS 환경 여부
     */
    public void clearJwtCookie(HttpServletResponse response, String name, boolean secure) {
        ResponseCookie cookie = ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .sameSite("None")
                .maxAge(0) // 즉시 만료
                .build();

        response.addHeader("Set-Cookie", cookie.toString());

        System.out.println("🍪 [쿠키 삭제] name=" + name + " | secure=" + secure);
    }
}
