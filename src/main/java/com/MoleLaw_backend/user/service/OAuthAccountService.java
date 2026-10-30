package com.MoleLaw_backend.user.service;

import com.MoleLaw_backend.user.entity.User;
import com.MoleLaw_backend.user.repository.UserRepository;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class OAuthAccountService {
    private final UserRepository users;
    private final Validator validator;

    @Transactional
    public User findOrCreate(String provider, String subject, String email, String nickname) {
        if (!("google".equals(provider) || "kakao".equals(provider))
                || subject == null || subject.isBlank() || subject.length() > 255
                || !subject.equals(subject.strip()) || subject.chars().anyMatch(Character::isISOControl)) {
            throw invalidAccount();
        }
        String normalizedEmail = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        if (!validator.validate(new OAuthEmail(normalizedEmail)).isEmpty()) throw invalidAccount();
        var existing = users.findByProviderAndProviderSubject(provider, subject);
        if (existing.isPresent()) {
            // Provider subject is immutable identity; profile changes never relink accounts.
            if (!subject.equals(existing.get().getProviderSubject())) throw invalidAccount();
            return existing.get();
        }
        if (users.existsByEmailAndProvider(normalizedEmail, provider)) throw invalidAccount();
        String displayName = nickname == null || nickname.isBlank() ? "user" : nickname.trim();
        if (displayName.length() > 50) {
            int end = 50;
            if (Character.isHighSurrogate(displayName.charAt(end - 1))) end--;
            displayName = displayName.substring(0, end);
        }
        try {
            return users.saveAndFlush(User.builder().provider(provider).providerSubject(subject)
                    .email(normalizedEmail).nickname(displayName).password(null).build());
        } catch (DataIntegrityViolationException collision) {
            // Concurrent first logins must fail closed rather than link by email.
            throw invalidAccount();
        }
    }

    private record OAuthEmail(@NotBlank @Email @Size(max = 254) String value) { }

    private static OAuth2AuthenticationException invalidAccount() {
        return new OAuth2AuthenticationException("invalid_oauth_account");
    }
}
