package com.MoleLaw_backend.service.oauth;

import com.MoleLaw_backend.service.security.CookieUtil;
import com.MoleLaw_backend.service.security.JwtUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtUtil jwtUtil;
    private final CookieUtil cookieUtil;

    private static final String COOKIE_NAME = "token";
    private static final boolean IS_SECURE = true; // HTTPS 환경이면 true, 로컬 테스트면 false

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication)
            throws IOException, ServletException {

        System.out.println("✅ [OAuth2SuccessHandler] 동작 시작"); // 🔥 로그 확인

        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String email = oAuth2User.getAttribute("email");

        if (email == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "이메일을 가져올 수 없습니다.");
            return;
        }

        // JWT 발급
        String token = jwtUtil.generateToken(email);
        System.out.println("✅ OAuth2 로그인 성공, JWT 발급: " + token);

        // 쿠키에 JWT 설정
        cookieUtil.addJwtCookie(response, COOKIE_NAME, token, IS_SECURE);

        // 인증 속성 초기화
        clearAuthenticationAttributes(request);


        // 프론트엔드 페이지로 리다이렉트
        response.sendRedirect("https://www.team-mole.shop/Main"); // 실제 프론트 주소로 수정
    }

}
