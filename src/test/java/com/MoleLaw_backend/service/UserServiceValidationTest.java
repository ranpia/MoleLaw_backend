package com.MoleLaw_backend.service;

import com.MoleLaw_backend.dto.request.SignupRequest;
import com.MoleLaw_backend.service.security.AuthTokenService;
import com.MoleLaw_backend.util.CookieUtil;
import com.MoleLaw_backend.service.security.RefreshTokenService;
import com.MoleLaw_backend.util.exception.ErrorCode;
import com.MoleLaw_backend.util.exception.MolelawException;
import com.MoleLaw_backend.entity.User;
import com.MoleLaw_backend.dao.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserServiceValidationTest {
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final AuthTokenService tokens = mock(AuthTokenService.class);
    private final UserService service = new UserService(users, passwords, mock(CookieUtil.class),
            tokens, mock(RefreshTokenService.class));

    @Test
    void normalizesEmailAndPreservesPasswordBeforeEncoding() {
        SignupRequest request = request();
        when(passwords.encode("  Password!234  ")).thenReturn("encoded-password");
        when(users.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.signup(request, new MockHttpServletResponse());

        verify(users).existsByEmailAndProvider("account@example.com", "local");
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("account@example.com");
        assertThat(saved.getValue().getPassword()).isEqualTo("encoded-password");
        assertThat(saved.getValue().getChatRooms()).isEmpty();
        verify(passwords).encode("  Password!234  ");
    }

    @Test
    void duplicateRaceReturnsDuplicateEmailWithoutIssuingTokens() {
        when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate",
                new ConstraintViolationException("duplicate", new SQLException("duplicate", "23000", 1062),
                        "user.uk_user_email_provider")));

        assertThatThrownBy(() -> service.signup(request(), new MockHttpServletResponse()))
                .isInstanceOfSatisfying(MolelawException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DUPLICATED_EMAIL));
        verifyNoInteractions(tokens);
    }

    @Test
    void unrelatedIntegrityViolationIsNotReportedAsDuplicateEmail() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("unrelated constraint");
        when(users.saveAndFlush(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.signup(request(), new MockHttpServletResponse())).isSameAs(failure);
        verifyNoInteractions(tokens);
    }

    private SignupRequest request() {
        SignupRequest request = new SignupRequest();
        request.setEmail("  Account@Example.COM  ");
        request.setPassword("  Password!234  ");
        request.setNickname("account");
        return request;
    }
}
