package com.MoleLaw_backend.service.oauth;

import com.MoleLaw_backend.domain.entity.User;
import com.MoleLaw_backend.domain.repository.UserRepository;
import com.MoleLaw_backend.service.security.CookieUtil;
import com.MoleLaw_backend.service.security.JwtUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${frontend.uri}")
    private String frontenduri;

    @Value("${cookie.secure:false}")
    private boolean isSecure;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication)
            throws IOException, ServletException {

        System.out.println("✅ [OAuth2SuccessHandler] 로그인 성공 핸들러 진입");

        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String email = oAuth2User.getAttribute("email");
        String provider = oAuth2User.getAttribute("provider");

        System.out.println("📧 이메일: " + email);
        System.out.println("🔗 제공자(provider): " + provider);

        if (email == null || provider == null) {
            System.out.println("❌ 이메일 또는 provider가 null입니다.");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "이메일 또는 provider를 가져올 수 없습니다.");
            return;
        }

        Optional<User> userOpt = userRepository.findByEmailAndProvider(email, provider);
        if (userOpt.isEmpty()) {
            System.out.println("❌ 해당 유저가 존재하지 않습니다.");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "해당 유저가 존재하지 않습니다.");
            return;
        }

        // ✅ JWT 발급
        String accessToken = jwtUtil.generateAccessToken(email, provider);
        String refreshToken = jwtUtil.generateRefreshToken(email, provider);

        System.out.println("🔐 accessToken 발급 완료: " + accessToken);
        System.out.println("🔐 refreshToken 발급 완료: " + refreshToken);

        // ✅ 쿠키 저장
        cookieUtil.addJwtCookie(response, "accessToken", accessToken, isSecure);
        cookieUtil.addJwtCookie(response, "refreshToken", refreshToken, isSecure);

        // ✅ 리다이렉트
        System.out.println("➡️ 리다이렉트 URL: " + frontenduri);
        response.sendRedirect(frontenduri);
    }
}


