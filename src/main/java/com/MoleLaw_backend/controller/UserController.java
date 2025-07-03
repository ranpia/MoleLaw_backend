package com.MoleLaw_backend.controller;

import com.MoleLaw_backend.dto.request.LoginRequest;
import com.MoleLaw_backend.dto.request.SignupRequest;
import com.MoleLaw_backend.dto.response.ApiResponse;
import com.MoleLaw_backend.dto.response.AuthResponse;
import com.MoleLaw_backend.dto.response.UserResponse;
import com.MoleLaw_backend.service.UserService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@Tag(name = "User", description = "사용자 로그인 관련 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/signup")
    public void signup(@RequestBody SignupRequest request, HttpServletResponse response) {
        AuthResponse authResponse = userService.signup(request);

        // ✅ JWT 쿠키 설정
        ResponseCookie accessCookie = ResponseCookie.from("accessToken", authResponse.getAccessToken())
                .httpOnly(true)
                .secure(false) // 로컬 환경은 false, 운영 시 true
                .path("/")
                .maxAge(60 * 60 * 24 * 7)
                .sameSite("Lax")
                .build();

        ResponseCookie refreshCookie = ResponseCookie.from("refreshToken", authResponse.getRefreshToken())
                .httpOnly(true)
                .secure(false)
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
                    .secure(false)
                    .sameSite("Lax")
                    .build();

            ResponseCookie refreshCookie = ResponseCookie.from("refreshToken", "")
                    .path("/")
                    .maxAge(0)
                    .httpOnly(true)
                    .secure(false)
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

}
