package com.MoleLaw_backend.controller;

import com.MoleLaw_backend.dto.request.LoginRequest;
import com.MoleLaw_backend.dto.request.SignupRequest;
import com.MoleLaw_backend.dto.response.ApiResponse;
import com.MoleLaw_backend.dto.response.AuthResponse;
import com.MoleLaw_backend.dto.response.UserResponse;
import com.MoleLaw_backend.service.UserService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody SignupRequest request) {
        AuthResponse response = userService.signup(request);
        return ResponseEntity.ok(response);
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

    @PostMapping("/reissue")
    public ResponseEntity<AuthResponse> reissue(@CookieValue(name = "refreshToken") String refreshToken) {
        AuthResponse response = userService.reissue(refreshToken);
        return ResponseEntity.ok(response);
    }
}
