package com.MoleLaw_backend.service.oauth;

import com.MoleLaw_backend.domain.entity.User;
import com.MoleLaw_backend.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        Map<String, Object> attributes = oAuth2User.getAttributes();

        final String email;
        final String nickname;

        if ("kakao".equals(registrationId)) {
            Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");
            Map<String, Object> profile = kakaoAccount != null ? (Map<String, Object>) kakaoAccount.get("profile") : null;

            if (kakaoAccount != null && kakaoAccount.get("email") != null) {
                email = (String) kakaoAccount.get("email");
            } else {
                throw new OAuth2AuthenticationException("카카오 계정에서 이메일을 찾을 수 없습니다.");
            }

            nickname = (profile != null && profile.get("nickname") != null)
                    ? (String) profile.get("nickname")
                    : "unknown";

        } else {
            email = (String) attributes.get("email");
            nickname = (String) attributes.getOrDefault("name", "unknown");

            if (email == null) {
                throw new OAuth2AuthenticationException("이메일을 가져올 수 없습니다.");
            }
        }

        // ✅ 이메일 + provider 기준으로 사용자 조회
        User user = userRepository.findByEmailAndProvider(email, registrationId)
                .orElseGet(() -> userRepository.save(
                        User.builder()
                                .email(email)
                                .nickname(nickname)
                                .password("") // 소셜 로그인은 패스워드 없음
                                .provider(registrationId)
                                .build()
                ));

        // 반환할 사용자 정보
        Map<String, Object> customAttributes = Map.of(
                "email", user.getEmail(),
                "nickname", user.getNickname()
        );

        return new DefaultOAuth2User(
                Collections.singleton(new SimpleGrantedAuthority("ROLE_USER")),
                customAttributes,
                "email"
        );
    }
}
