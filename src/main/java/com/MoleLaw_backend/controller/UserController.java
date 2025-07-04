package com.MoleLaw_backend.controller;

import com.MoleLaw_backend.dto.request.ChangeNicknameRequest;
import com.MoleLaw_backend.dto.request.ChangePasswordRequest;
import com.MoleLaw_backend.dto.request.LoginRequest;
import com.MoleLaw_backend.dto.request.SignupRequest;
import com.MoleLaw_backend.dto.response.ApiResponse;
import com.MoleLaw_backend.dto.response.AuthResponse;
import com.MoleLaw_backend.dto.response.UserResponse;
import com.MoleLaw_backend.service.security.CustomUserDetails;
import com.MoleLaw_backend.service.user.UserService;
import com.MoleLaw_backend.service.security.CookieUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@Tag(name = "User", description = "사용자 로그인 관련 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final CookieUtil cookieUtil;
    private static final boolean IS_SECURE = true;

    @PostMapping("/signup")
    public void signup(@RequestBody SignupRequest request, HttpServletResponse response) {
        AuthResponse authResponse = userService.signup(request);

        // ✅ JWT 쿠키 설정
        ResponseCookie accessCookie = ResponseCookie.from("accessToken", authResponse.getAccessToken())
                .httpOnly(true)
                .secure(IS_SECURE) // 로컬 환경은 false, 운영 시 true
                .path("/")
                .maxAge(60 * 60 * 24 * 7)
                .sameSite("Lax")
                .build();

        ResponseCookie refreshCookie = ResponseCookie.from("refreshToken", authResponse.getRefreshToken())
                .httpOnly(true)
                .secure(IS_SECURE)
                .path("/")
                .maxAge(60 * 60 * 24 * 7)
                .sameSite("Lax")
                .build();

        response.addHeader("Set-Cookie", accessCookie.toString());
        response.addHeader("Set-Cookie", refreshCookie.toString());

        // ✅ /chat 페이지로 리다이렉트
        response.setStatus(HttpServletResponse.SC_FOUND); // 302
        response.setHeader("Location", "/Main");
    }


    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request, HttpServletResponse response) {
        AuthResponse authResponse = userService.login(request, response);
        return ResponseEntity.ok(authResponse);
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "BearerAuth")
    public ResponseEntity<ApiResponse<UserResponse>> getMyInfo() {
        String subject = SecurityContextHolder.getContext().getAuthentication().getName();
        String[] parts = subject.split(":");

        if (parts.length != 2) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.<UserResponse>error("잘못된 사용자 식별자입니다."));
        }

        String email = parts[0];
        String provider = parts[1];

        UserResponse user = userService.getUserByEmailAndProvider(email, provider);
        return ResponseEntity.ok(ApiResponse.success(user));
    }

    @DeleteMapping("/me")
    @SecurityRequirement(name = "BearerAuth")
    public ResponseEntity<Void> deleteUser(HttpServletResponse response) {
        try {
            String subject = SecurityContextHolder.getContext().getAuthentication().getName();
            String[] parts = subject.split(":");

            if (parts.length != 2) {
                System.out.println("❌ 잘못된 subject 구조: " + subject);
                return ResponseEntity.badRequest().build();
            }

            String email = parts[0];
            String provider = parts[1];

            System.out.println("🔍 DELETE 요청 대상 유저: " + email + " / " + provider);
            userService.deleteUser(email, provider);

            // 쿠키 삭제
            ResponseCookie accessCookie = ResponseCookie.from("accessToken", "")
                    .path("/")
                    .maxAge(0)
                    .httpOnly(true)
                    .secure(IS_SECURE)
                    .sameSite("Lax")
                    .build();

            ResponseCookie refreshCookie = ResponseCookie.from("refreshToken", "")
                    .path("/")
                    .maxAge(0)
                    .httpOnly(true)
                    .secure(IS_SECURE)
                    .sameSite("Lax")
                    .build();

            response.addHeader("Set-Cookie", accessCookie.toString());
            response.addHeader("Set-Cookie", refreshCookie.toString());

            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("❌ DELETE /me 처리 중 예외 발생: " + e.getMessage());
        }
    }

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

    @PatchMapping("/password")
    @Operation(
            summary = "비밀번호 변경 (local 전용)",
            description = "로그인한 local 사용자의 비밀번호를 새 비밀번호로 변경합니다.",
            security = @SecurityRequirement(name = "BearerAuth")
    )
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal CustomUserDetails userDetails,
                                               @RequestBody ChangePasswordRequest request) {
        userService.changePassword(userDetails.getEmail(), userDetails.getProvider(), request);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/nickname")
    @Operation(
            summary = "닉네임 변경",
            description = "로그인한 사용자의 닉네임을 새 닉네임으로 변경합니다.",
            security = @SecurityRequirement(name = "BearerAuth")
    )
    public ResponseEntity<Void> changeNickname(@AuthenticationPrincipal CustomUserDetails userDetails,
                                               @RequestBody ChangeNicknameRequest request) {
        userService.changeNickname(userDetails.getEmail(), userDetails.getProvider(), request);
        return ResponseEntity.ok().build();
    }
}
