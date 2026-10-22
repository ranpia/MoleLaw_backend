package com.MoleLaw_backend.auth.oauth;

import com.MoleLaw_backend.user.entity.User;
import com.MoleLaw_backend.user.service.OAuthAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {
    private final OAuthAccountService accounts;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User remote = super.loadUser(request);
        return resolveUser(request.getClientRegistration().getRegistrationId(), remote.getAttributes());
    }

    OAuth2User resolveUser(String provider, Map<String, Object> attributes) {
        final String subject;
        final String email;
        final String nickname;
        if ("google".equals(provider)) {
            subject = requiredString(attributes.get("sub"));
            email = requiredString(attributes.get("email"));
            nickname = optionalString(attributes.get("name"));
        } else if ("kakao".equals(provider)) {
            Object id = attributes.get("id");
            subject = id == null ? "" : id.toString();
            if (!subject.matches("[1-9][0-9]{0,19}")) throw invalidClaims();
            if (!(attributes.get("kakao_account") instanceof Map<?, ?> account)) throw invalidClaims();
            email = requiredString(account.get("email"));
            nickname = account.get("profile") instanceof Map<?, ?> profile
                    ? optionalString(profile.get("nickname")) : "user";
        } else {
            throw invalidClaims();
        }
        User user = accounts.findOrCreate(provider, subject, email, nickname);
        return new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("userId", user.getId(), "provider", user.getProvider(),
                        "providerSubject", user.getProviderSubject(), "email", user.getEmail(),
                        "nickname", user.getNickname()), "userId");
    }

    private static String requiredString(Object value) {
        if (!(value instanceof String text) || text.isBlank()) throw invalidClaims();
        return text;
    }

    private static String optionalString(Object value) {
        return value instanceof String text ? text : "user";
    }

    private static OAuth2AuthenticationException invalidClaims() {
        return new OAuth2AuthenticationException("invalid_oauth_claims");
    }
}
