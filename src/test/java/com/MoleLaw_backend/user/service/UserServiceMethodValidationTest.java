package com.MoleLaw_backend.user.service;

import com.MoleLaw_backend.auth.dto.request.LoginRequest;
import com.MoleLaw_backend.auth.dto.request.SignupRequest;
import com.MoleLaw_backend.auth.security.AuthTokenService;
import com.MoleLaw_backend.auth.security.CookieUtil;
import com.MoleLaw_backend.auth.security.RefreshTokenService;
import com.MoleLaw_backend.user.dto.request.ChangeNicknameRequest;
import com.MoleLaw_backend.user.dto.request.ChangePasswordRequest;
import com.MoleLaw_backend.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class UserServiceMethodValidationTest {
    @Test
    void springProxyRejectsInvalidAndNullRequestsBeforeAnyDependencyAccess() throws Exception {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        CookieUtil cookies = mock(CookieUtil.class);
        AuthTokenService tokens = mock(AuthTokenService.class);
        RefreshTokenService refresh = mock(RefreshTokenService.class);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MethodValidationPostProcessor.class, () -> {
                MethodValidationPostProcessor processor = new MethodValidationPostProcessor();
                processor.setProxyTargetClass(true);
                return processor;
            });
            context.registerBean(UserService.class, () -> new UserService(users, passwords, cookies, tokens, refresh));
            context.refresh();
            UserService service = context.getBean(UserService.class);
            assertTrue(AopUtils.isAopProxy(service));
            ObjectMapper mapper = new ObjectMapper();
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertThrows(ConstraintViolationException.class, () -> service.signup(null, response));
            assertThrows(ConstraintViolationException.class, () -> service.login(null, response));
            assertThrows(ConstraintViolationException.class, () -> service.changePassword(1L, null));
            assertThrows(ConstraintViolationException.class, () -> service.changeNickname(1L, null));
            assertThrows(ConstraintViolationException.class, () -> service.signup(new SignupRequest(), response));
            assertThrows(ConstraintViolationException.class, () -> service.login(new LoginRequest(), response));
            assertThrows(ConstraintViolationException.class,
                    () -> service.changePassword(1L, new ChangePasswordRequest()));
            assertThrows(ConstraintViolationException.class,
                    () -> service.changeNickname(1L, new ChangeNicknameRequest()));
            SignupRequest unicodePassword = mapper.readValue("{\"email\":\"user@example.com\",\"nickname\":\"account\","
                    + "\"password\":\"" + "한".repeat(25) + "\"}", SignupRequest.class);
            assertThrows(ConstraintViolationException.class, () -> service.signup(unicodePassword, response));
            verifyNoInteractions(users, passwords, cookies, tokens, refresh);
        }
    }
}
