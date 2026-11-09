package com.MoleLaw_backend.auth.oauth;

import com.MoleLaw_backend.common.exception.GlobalExceptionHandler;

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

@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final AuthTokenService authTokens;
    private final GlobalExceptionHandler errors;

    @Value("${frontend.uri}")
    private String frontenduri;

    @Override
    @jakarta.transaction.Transactional
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication)
            throws IOException, ServletException {

        if (!(authentication.getPrincipal() instanceof OAuth2User principal)) {
            errors.writeError(response, new MolelawException(ErrorCode.UNAUTHORIZED), ErrorCode.UNAUTHORIZED);
            return;
        }
        Object userId = principal.getAttribute("userId");
        Object providerValue = principal.getAttribute("provider");
        Object subjectValue = principal.getAttribute("providerSubject");
        if (!(userId instanceof Long id) || id <= 0
                || !(providerValue instanceof String provider)
                || !(subjectValue instanceof String subject) || subject.isBlank()
                || !("google".equals(provider) || "kakao".equals(provider))) {
            errors.writeError(response, new MolelawException(ErrorCode.UNAUTHORIZED), ErrorCode.UNAUTHORIZED);
            return;
        }
        User user = userRepository.findByIdForUpdate(id).orElse(null);
        if (user == null || !provider.equals(user.getProvider())
                || !subject.equals(user.getProviderSubject())) {
            errors.writeError(response, new MolelawException(ErrorCode.UNAUTHORIZED), ErrorCode.UNAUTHORIZED);
            return;
        }

        try {
            authTokens.issue(user.getId(), response);
        } catch (MolelawException e) {
            errors.writeError(response, e, ErrorCode.INTERNAL_SERVER_ERROR);
            return;
        }
        response.sendRedirect(frontenduri);
    }
}

