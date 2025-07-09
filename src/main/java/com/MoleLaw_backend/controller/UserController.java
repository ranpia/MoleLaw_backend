package com.MoleLaw_backend.controller;

import com.MoleLaw_backend.dto.request.ChangeNicknameRequest;
import com.MoleLaw_backend.dto.request.ChangePasswordRequest;
import com.MoleLaw_backend.dto.request.LoginRequest;
import com.MoleLaw_backend.dto.request.SignupRequest;
import com.MoleLaw_backend.dto.response.ApiResponse;
import com.MoleLaw_backend.dto.response.AuthResponse;
import com.MoleLaw_backend.dto.response.UserResponse;
import com.MoleLaw_backend.service.chat.ChatService;
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

    private final ChatService chatService;
    private final UserService userService;
    private final CookieUtil cookieUtil;
    private static final boolean IS_SECURE = true;

    @Operation(summary = "회원가입", description = "이메일 중복 여부 확인 후 회원가입 처리")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "회원가입 성공 및 쿠키 발급"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "이메일 중복"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PostMapping("/signup")
    public void signup(@RequestBody SignupRequest request, HttpServletResponse response) {
        AuthResponse authResponse = userService.signup(request);

        ResponseCookie accessCookie = ResponseCookie.from("accessToken", authResponse.getAccessToken())
                .httpOnly(true)
                .secure(IS_SECURE)
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
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", "/Main");
    }

    @Operation(summary = "로그인", description = "이메일+비밀번호 또는 소셜 계정으로 로그인 처리")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "이메일 또는 비밀번호 불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "잘못된 로그인 시도 (소셜 계정 등)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request, HttpServletResponse response) {
        AuthResponse authResponse = userService.login(request, response);
        return ResponseEntity.ok(authResponse);
    }

    @Operation(summary = "내 정보 조회", description = "JWT로 인증된 사용자의 정보 반환", security = @SecurityRequirement(name = "BearerAuth"))
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 subject 구조"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자 없음")
    })
    @GetMapping("/me")
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

    @Operation(summary = "회원 탈퇴", description = "사용자 정보를 삭제하고 JWT 쿠키를 제거", security = @SecurityRequirement(name = "BearerAuth"))
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "회원 탈퇴 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "subject 구조가 잘못됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자 정보 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteUser(HttpServletResponse response) {
        try {
            String subject = SecurityContextHolder.getContext().getAuthentication().getName();
            String[] parts = subject.split(":");

            if (parts.length != 2) {
                return ResponseEntity.badRequest().build();
            }

            String email = parts[0];
            String provider = parts[1];
            userService.deleteUser(email, provider);

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
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그아웃 성공")
    })
    @PostMapping(value = "/logout", consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<String>> logout(HttpServletResponse response) {
        cookieUtil.clearJwtCookie(response, "accessToken", IS_SECURE);
        cookieUtil.clearJwtCookie(response, "refreshToken", IS_SECURE);
        return ResponseEntity.ok(ApiResponse.success("로그아웃 되었습니다."));
    }

    @Operation(summary = "액세스 토큰 재발급", description = "리프레시 토큰으로 Access Token 재발급")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "토큰 재발급 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Refresh 토큰 누락 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "유효하지 않은 토큰"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "토큰 발급 실패")
    })
    @PostMapping("/reissue")
    public ResponseEntity<ApiResponse<AuthResponse>> reissue(HttpServletRequest request, HttpServletResponse response) {
        AuthResponse authResponse = userService.reissue(request, response);
        return ResponseEntity.ok(ApiResponse.success(authResponse));
    }

    @Operation(summary = "비밀번호 변경 (local 전용)", description = "로그인한 local 사용자의 비밀번호를 새 비밀번호로 변경합니다.", security = @SecurityRequirement(name = "BearerAuth"))
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "비밀번호 변경 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "현재 비밀번호 불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "소셜 로그인 사용자는 변경 불가"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "비밀번호 변경 실패")
    })
    @PatchMapping("/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal CustomUserDetails userDetails,
                                               @RequestBody ChangePasswordRequest request) {
        userService.changePassword(userDetails.getEmail(), userDetails.getProvider(), request);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "닉네임 변경", description = "로그인한 사용자의 닉네임을 새 닉네임으로 변경합니다.", security = @SecurityRequirement(name = "BearerAuth"))
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "닉네임 변경 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "닉네임 유효성 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "중복된 닉네임"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "닉네임 변경 실패")
    })
    @PatchMapping("/nickname")
    public ResponseEntity<Void> changeNickname(@AuthenticationPrincipal CustomUserDetails userDetails,
                                               @RequestBody ChangeNicknameRequest request) {
        userService.changeNickname(userDetails.getEmail(), userDetails.getProvider(), request);
        return ResponseEntity.ok().build();
    }
}
