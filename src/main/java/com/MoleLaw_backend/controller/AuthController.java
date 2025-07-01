package com.MoleLaw_backend.controller;

import com.MoleLaw_backend.dto.response.ApiResponse;
import com.MoleLaw_backend.dto.response.AuthResponse;
import com.MoleLaw_backend.service.UserService;
import com.MoleLaw_backend.service.security.CookieUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Auth", description = "인증 관련 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final CookieUtil cookieUtil;
    private final UserService userService;

    private static final boolean IS_SECURE = true;

    @Operation(summary = "로그아웃", description = "JWT 기반 로그아웃 (쿠키 삭제)")
    @PostMapping(value = "/logout", consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<String>> logout(HttpServletResponse response) {
        System.out.println("✅ 로그아웃 API 호출됨");
        cookieUtil.clearJwtCookie(response, "accessToken", IS_SECURE);
        cookieUtil.clearJwtCookie(response, "refreshToken", IS_SECURE);
        return ResponseEntity.ok(ApiResponse.success("로그아웃 되었습니다."));
    }

    @Operation(summary = "액세스 토큰 재발급", description = "리프레시 토큰으로 Access Token 재발급")
    @PostMapping("/reissue")
    public ResponseEntity<ApiResponse<AuthResponse>> reissue(HttpServletRequest request, HttpServletResponse response) {
        AuthResponse authResponse = userService.reissue(request, response);
        return ResponseEntity.ok(ApiResponse.success(authResponse));
    }
}
