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
    private final UserRepository userRepository; // ✅ 주입 필요

    private static final String COOKIE_NAME = "token";
    private static final boolean IS_SECURE = true;

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

        // ✅ Access + Refresh 동시 발급
        String accessToken = jwtUtil.generateAccessToken(email, provider);
        String refreshToken = jwtUtil.generateRefreshToken(email, provider);

        // ✅ Access Token → 헤더
        response.setHeader("Authorization", "Bearer " + accessToken);

        // ✅ Refresh Token → 쿠키 (HttpOnly)
        cookieUtil.addJwtCookie(response, "refreshToken", refreshToken, IS_SECURE);

        // ✅ 리다이렉트
        response.sendRedirect("https://www.team-mole.shop/Main");
    }

}
