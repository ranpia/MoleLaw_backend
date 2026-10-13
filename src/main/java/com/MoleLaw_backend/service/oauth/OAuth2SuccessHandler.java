package com.MoleLaw_backend.service.oauth;

import com.MoleLaw_backend.domain.entity.User;
import com.MoleLaw_backend.domain.repository.UserRepository;
import com.MoleLaw_backend.service.security.CookieUtil;
import com.MoleLaw_backend.service.security.JwtUtil;
import com.MoleLaw_backend.service.security.AccessTokenService;
import com.MoleLaw_backend.exception.MolelawException;
import com.MoleLaw_backend.exception.ErrorCode;
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
    private final AccessTokenService accessTokenService;

    @Value("${frontend.uri}")
    private String frontenduri;


    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication)
            throws IOException, ServletException {


        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String email = oAuth2User.getAttribute("email");
        String provider = oAuth2User.getAttribute("provider"); // 예: "google"


        if (email == null || provider == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "이메일 또는 provider를 가져올 수 없습니다.");
            return;
        }

        // ✅ 유저가 존재하지 않으면 새로 저장
        User user = userRepository.findByEmailAndProvider(email, provider)
                .orElseGet(() -> {
                    User newUser = User.builder()
                            .email(email)
                            .provider(provider)
                            .build();
                    return userRepository.save(newUser);
                });

        // ✅ JWT 발급
        String accessToken;
        try {
            accessToken = accessTokenService.issue(email, provider);
        } catch (MolelawException e) {
            if (e.getErrorCode() != ErrorCode.AUTH_STORE_UNAVAILABLE) {
                throw e;
            }
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"AUTH_STORE_UNAVAILABLE\"}");
            return;
        }
        String refreshToken = jwtUtil.generateRefreshToken(email, provider);


        // ✅ 쿠키 저장
        cookieUtil.addJwtCookie(response, "accessToken", accessToken);
        cookieUtil.addJwtCookie(response, "refreshToken", refreshToken);

        // ✅ 리다이렉트
        response.sendRedirect(frontenduri);
    }
}


