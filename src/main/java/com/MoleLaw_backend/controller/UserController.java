package com.MoleLaw_backend.controller;

import com.MoleLaw_backend.dto.request.LoginRequest;
import com.MoleLaw_backend.dto.request.SignupRequest;
import com.MoleLaw_backend.dto.response.ApiResponse;
import com.MoleLaw_backend.dto.response.AuthResponse;
import com.MoleLaw_backend.dto.response.UserResponse;
import com.MoleLaw_backend.service.UserService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement; // ✅ 추가
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
    public ResponseEntity<?> signup(@RequestBody SignupRequest request, HttpServletResponse response) {
        userService.signup(request, response);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request, HttpServletResponse response) {
        userService.login(request, response);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "BearerAuth") // ✅ 추가
    public ResponseEntity<ApiResponse<UserResponse>> getMyInfo() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        UserResponse user = userService.getUserByEmail(email);
        return ResponseEntity.ok(ApiResponse.success(user));
    }
}
