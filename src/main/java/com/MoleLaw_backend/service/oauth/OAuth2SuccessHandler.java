package com.MoleLaw_backend.service.oauth;

import com.MoleLaw_backend.domain.entity.User;
import com.MoleLaw_backend.domain.repository.UserRepository;
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
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtUtil jwtUtil;
    private final CookieUtil cookieUtil;
    private final UserRepository userRepository;

    private static final boolean IS_SECURE = false; // 로컬이므로 false

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication)
            throws IOException, ServletException {

        System.out.println("✅ [OAuth2SuccessHandler] 동작 시작");

        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String email = oAuth2User.getAttribute("email");
        String provider = oAuth2User.getAttribute("provider");

        if (email == null || provider == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "이메일 또는 provider를 가져올 수 없습니다.");
            return;
        }

        Optional<User> userOpt = userRepository.findByEmailAndProvider(email, provider);
        if (userOpt.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "해당 유저가 존재하지 않습니다.");
            return;
        }

        // ✅ JWT 발급
        String accessToken = jwtUtil.generateAccessToken(email, provider);
        String refreshToken = jwtUtil.generateRefreshToken(email, provider);

        // ✅ 쿠키 저장
        cookieUtil.addJwtCookie(response, "accessToken", accessToken, false);
        cookieUtil.addJwtCookie(response, "refreshToken", refreshToken, false);

        // ✅ 강제적으로 헤더를 커밋하고 리다이렉트는 직접 HTML로 유도
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write("<script>window.location.href='http://localhost:5173/Main';</script>");
        response.getWriter().flush(); // flushBuffer 대신 getWriter().flush()
    }

}
