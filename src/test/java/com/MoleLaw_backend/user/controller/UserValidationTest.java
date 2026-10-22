package com.MoleLaw_backend.user.controller;

import com.MoleLaw_backend.auth.security.AuthTokenService;
import com.MoleLaw_backend.auth.security.CookieUtil;
import com.MoleLaw_backend.common.exception.GlobalExceptionHandler;
import com.MoleLaw_backend.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserValidationTest {
    private final UserService service = mock(UserService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new UserController(service, mock(CookieUtil.class)), new com.MoleLaw_backend.auth.controller.AuthController(service, mock(AuthTokenService.class)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

    @Test
    void invalidSignupCannotInvokeService() throws Exception {
        for (String email : new String[]{"", " ", "bad-email", "x".repeat(255) + "@example.com"}) {
            invalid(post("/api/auth/signup"), Map.of("email", email, "password", "password123", "nickname", "두더지"));
        }
        for (String password : new String[]{"", " ", "short", "x".repeat(73), "한".repeat(25)}) {
            invalid(post("/api/auth/signup"), Map.of("email", "user@example.com", "password", password, "nickname", "두더지"));
        }
        for (String nickname : new String[]{"", " ", "a", "x".repeat(51)}) {
            invalid(post("/api/auth/signup"), Map.of("email", "user@example.com", "password", "password123", "nickname", nickname));
        }
        invalid(post("/api/auth/signup"), Map.of());
        verifyNoInteractions(service);
    }

    @Test
    void invalidLoginAndChangesCannotInvokeService() throws Exception {
        invalid(post("/api/auth/login"), Map.of("email", "invalid", "password", "password123"));
        invalid(post("/api/auth/login"), Map.of("email", "user@example.com", "password", "한".repeat(25)));
        invalid(post("/api/auth/login"), Map.of());
        for (String password : new String[]{"", " ", "short", "x".repeat(73), "한".repeat(25)}) {
            invalid(patch("/api/users/me/password"), Map.of("newPassword", password));
        }
        invalid(patch("/api/users/me/password"), Map.of());
        for (String nickname : new String[]{"", " ", "a", "x".repeat(51)}) {
            invalid(patch("/api/users/me/nickname"), Map.of("newNickname", nickname));
        }
        invalid(patch("/api/users/me/nickname"), Map.of());
        verifyNoInteractions(service);
    }

    @Test
    void malformedAndMissingBodiesReturnSafeError() throws Exception {
        for (String path : new String[]{"signup", "login", "password", "nickname"}) {
            for (String body : new String[]{"", "{\"password\":\"secret-invalid-json", "null"}) {
                MockHttpServletRequestBuilder request = path.equals("password") || path.equals("nickname")
                        ? patch("/api/users/me/" + path) : post("/api/auth/" + path);
                mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
            }
        }
        verifyNoInteractions(service);
    }

    @Test
    void validUnicodePasswordAtBcryptBoundaryReachesService() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", "user@example.com",
                                "password", "한".repeat(24), "nickname", "두더지 !"))))
                .andExpect(status().isOk());
        verify(service).signup(any(), any());
    }

    @Test
    void loginDoesNotReapplySignupMinimumPasswordLength() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"x\"}"))
                .andExpect(status().isOk());
        verify(service).login(any(), any());
    }

    private void invalid(MockHttpServletRequestBuilder request, Map<String, String> body) throws Exception {
        mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("잘못된 요청입니다."));
    }
}
