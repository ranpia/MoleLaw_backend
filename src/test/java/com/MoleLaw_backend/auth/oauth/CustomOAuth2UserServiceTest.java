package com.MoleLaw_backend.auth.oauth;

import com.MoleLaw_backend.user.entity.User;
import com.MoleLaw_backend.user.service.OAuthAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomOAuth2UserServiceTest {
    private final OAuthAccountService accounts = mock(OAuthAccountService.class);
    private final CustomOAuth2UserService service = new CustomOAuth2UserService(accounts);

    @Test
    void googleUsesSubjectRatherThanEmailAsIdentity() {
        when(accounts.findOrCreate("google", "stable", "mail@example.com", "Name"))
                .thenReturn(User.builder().id(4L).provider("google").providerSubject("stable")
                        .email("mail@example.com").nickname("Name").build());
        var user = service.resolveUser("google", Map.of("sub", "stable", "email", "mail@example.com", "name", "Name"));
        assertThat(user.getName()).isEqualTo("4");
        assertThat((Long) user.getAttribute("userId")).isEqualTo(4L);
    }

    @Test
    void kakaoUsesPositiveNumericId() {
        when(accounts.findOrCreate("kakao", "123", "mail@example.com", "user"))
                .thenReturn(User.builder().id(4L).provider("kakao").providerSubject("123")
                        .email("mail@example.com").nickname("user").build());
        service.resolveUser("kakao", Map.of("id", 123L, "kakao_account", Map.of("email", "mail@example.com")));
        verify(accounts).findOrCreate("kakao", "123", "mail@example.com", "user");
    }

    @Test
    void rejectsMissingMalformedAndUnsupportedClaims() {
        assertThatThrownBy(() -> service.resolveUser("google", Map.of("email", "mail@example.com")))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> service.resolveUser("kakao", Map.of("id", -1L)))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> service.resolveUser("other", Map.of()))
                .isInstanceOf(OAuth2AuthenticationException.class);
        verifyNoInteractions(accounts);
    }
}
