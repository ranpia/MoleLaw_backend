package com.MoleLaw_backend.user.service;

import com.MoleLaw_backend.user.entity.User;
import com.MoleLaw_backend.user.repository.UserRepository;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OAuthAccountServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final OAuthAccountService service = new OAuthAccountService(users,
            Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void changedEmailKeepsStableSubjectAccountWithoutRelinking() {
        User existing = User.builder().id(1L).provider("google").providerSubject("Subject")
                .email("original@example.com").nickname("user").build();
        when(users.findByProviderAndProviderSubject("google", "Subject")).thenReturn(Optional.of(existing));
        assertThat(service.findOrCreate("google", "Subject", "new@example.com", "new name"))
                .isSameAs(existing);
        verify(users, never()).saveAndFlush(any());
        assertThat(existing.getEmail()).isEqualTo("original@example.com");
    }

    @Test
    void differentSubjectCannotClaimExistingEmail() {
        when(users.existsByEmailAndProvider("same@example.com", "google")).thenReturn(true);
        assertThatThrownBy(() -> service.findOrCreate("google", "other", " SAME@example.com ", "name"))
                .isInstanceOf(OAuth2AuthenticationException.class);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void exactSubjectCaseIsRequiredEvenIfDatabaseReturnsDifferentCase() {
        when(users.findByProviderAndProviderSubject("google", "subject"))
                .thenReturn(Optional.of(User.builder().providerSubject("Subject").build()));
        assertThatThrownBy(() -> service.findOrCreate("google", "subject", "mail@example.com", "name"))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void createsOauthAccountWithNormalizedEmailAndNoPassword() {
        when(users.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        User result = service.findOrCreate("kakao", "1234", " MAIL@example.com ", null);
        assertThat(result.getEmail()).isEqualTo("mail@example.com");
        assertThat(result.getProviderSubject()).isEqualTo("1234");
        assertThat(result.getPassword()).isNull();
        assertThat(result.getNickname()).isEqualTo("user");
    }

    @Test
    void invalidClaimsAreRejectedBeforeRepositoryAccess() {
        assertThatThrownBy(() -> service.findOrCreate("unsupported", "id", "a@b.com", "name"))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> service.findOrCreate("google", "", "a@b.com", "name"))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> service.findOrCreate("google", "id", "broken", "name"))
                .isInstanceOf(OAuth2AuthenticationException.class);
        verifyNoInteractions(users);
    }
}
