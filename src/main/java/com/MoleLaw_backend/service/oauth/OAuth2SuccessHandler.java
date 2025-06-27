package com.MoleLaw_backend.service.oauth;

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

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication)
            throws IOException, ServletException {

        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String email = oAuth2User.getAttribute("email");

        if (email == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "이메일을 가져올 수 없습니다.");
            return;
        }

        String token = jwtUtil.generateToken(email);
        System.out.println("✅ OAuth2 로그인 성공, JWT 발급: " + token);

        clearAuthenticationAttributes(request);

        // ✅ 프론트엔드 주소로 리디렉션하면서 토큰 전달
        String redirectUrl = "https://team-mole.shop/oauth2/success?token=" + token;
        response.sendRedirect(redirectUrl);
    }

}
