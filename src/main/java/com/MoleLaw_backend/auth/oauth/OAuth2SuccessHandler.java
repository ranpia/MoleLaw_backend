package com.MoleLaw_backend.auth.oauth;

import com.MoleLaw_backend.user.entity.User;
import com.MoleLaw_backend.user.repository.UserRepository;
import com.MoleLaw_backend.auth.security.AuthTokenService;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.common.exception.ErrorCode;
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

    private final UserRepository userRepository;
    private final AuthTokenService authTokens;

    @Value("${frontend.uri}")
    private String frontenduri;


    @Override
    @jakarta.transaction.Transactional
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
        User user = userRepository.findByCredentialsForUpdate(email, provider)
                .orElseGet(() -> {
                    User newUser = User.builder()
                            .email(email)
                            .provider(provider)
                            .build();
                    return userRepository.save(newUser);
                });

        // ✅ JWT 발급
        try {
            authTokens.issue(user.getId(), response);
        } catch (MolelawException e) {
            if (e.getErrorCode() != ErrorCode.AUTH_STORE_UNAVAILABLE
                    && e.getErrorCode() != ErrorCode.INVALID_REFRESH_TOKEN) {
                throw e;
            }
            response.setStatus(e.getErrorCode().getStatus());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"" + e.getErrorCode().name() + "\"}");
            return;
        }
        // ✅ 리다이렉트
        response.sendRedirect(frontenduri);
    }
}


