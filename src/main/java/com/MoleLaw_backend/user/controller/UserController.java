package com.MoleLaw_backend.user.controller;

import com.MoleLaw_backend.user.dto.response.UserApiResponse;
import com.MoleLaw_backend.auth.dto.response.AuthApiSchemas.UnauthorizedSchema;
import com.MoleLaw_backend.auth.dto.response.AuthApiSchemas.ErrorEnvelope;

import com.MoleLaw_backend.user.dto.request.ChangeNicknameRequest;
import com.MoleLaw_backend.user.dto.request.ChangePasswordRequest;
import com.MoleLaw_backend.common.dto.response.ApiResponse;
import com.MoleLaw_backend.user.dto.response.UserResponse;
import com.MoleLaw_backend.auth.security.CustomUserDetails;
import com.MoleLaw_backend.user.service.UserService;
import com.MoleLaw_backend.auth.security.CookieUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;

@Tag(name = "User", description = "내 정보·계정 삭제·프로필 관리")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users/me")
public class UserController {
    private final UserService userService;
    private final CookieUtil cookieUtil;
    @Operation(summary = "내 정보 조회", description = "Access JWT는 Authorization: Bearer 헤더 또는 accessToken 쿠키로 전달합니다. 둘 다 있으면 Bearer가 우선입니다. JWT subject의 User.id로 현재 사용자의 이메일과 닉네임을 조회합니다. 구형 이메일 subject 토큰은 401로 거절합니다.", security = @SecurityRequirement(name = "AccessCookie"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공", content = @Content(mediaType = "application/json", schema = @Schema(implementation = UserApiResponse.class), examples = @ExampleObject(value = "{\"success\":true,\"data\":{\"email\":\"user@example.com\",\"nickname\":\"두더지\"}}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Security 필터 인증 실패. ApiResponse 래퍼 없이 반환", content = @Content(mediaType = "application/json", schema = @Schema(implementation = UnauthorizedSchema.class), examples = @ExampleObject(value = "{\"error\":\"Unauthorized\"}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "해당 사용자를 찾을 수 없습니다.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorEnvelope.class), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":502,\"code\":\"USER_NOT_FOUND\",\"message\":\"해당 사용자를 찾을 수 없습니다.\"}}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorEnvelope.class), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))
    })
    @GetMapping
    public ResponseEntity<ApiResponse<UserResponse>> getMyInfo(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails) {
        UserResponse user = userService.getUserById(userDetails.getUserId());
        return ResponseEntity.ok(ApiResponse.success(user));
    }

    @Operation(summary = "회원 탈퇴", description = "Access JWT는 Authorization: Bearer 헤더 또는 accessToken 쿠키로 전달합니다. 둘 다 있으면 Bearer가 우선입니다. 인증된 User.id의 모든 Refresh family를 폐기한 뒤 사용자·연관 채팅 기록과 인증 쿠키를 삭제합니다. Redis 폐기 실패는 503이며 DB 삭제를 롤백합니다. 먼저 GET /api/auth/csrf를 호출해 받은 쿠키를 유지하고 응답 token을 X-XSRF-TOKEN 헤더에 넣습니다. 같은 서버의 Swagger에서는 CSRF 조회 후 쿠키에서 토큰을 읽어 헤더에 자동 적용합니다. Bearer 인증에도 CSRF 헤더가 필요하며 Origin은 같은 서버 주소 또는 허용 목록의 주소여야 합니다.", security = @SecurityRequirement(name = "AccessCookie"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "탈퇴 성공. 본문 없이 JWT 쿠키 삭제", headers = @Header(name = "Set-Cookie", description = "accessToken Path=/ 및 refreshToken Path=/api/auth 삭제. 이전 Path=/ Refresh도 삭제", schema = @Schema(type = "string")), content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "인증 후 탈퇴 처리 전에 사용자가 삭제된 경우", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorEnvelope.class), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":502,\"code\":\"USER_NOT_FOUND\",\"message\":\"해당 사용자를 찾을 수 없습니다.\"}}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Security 필터 인증 실패. ApiResponse 래퍼 없이 반환", content = @Content(mediaType = "application/json", schema = @Schema(implementation = UnauthorizedSchema.class), examples = @ExampleObject(value = "{\"error\":\"Unauthorized\"}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 누락·불일치 또는 허용되지 않은 Origin. Security/서블릿 오류 응답은 ApiResponse JSON 계약이 없으며 컨테이너에 따라 본문이 달라집니다.", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorEnvelope.class), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))
    })
    @DeleteMapping
    public ResponseEntity<Void> deleteUser(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            HttpServletResponse response) {
        userService.deleteUser(userDetails.getUserId());
        cookieUtil.clearJwtCookie(response, "accessToken");
        cookieUtil.clearJwtCookie(response, "refreshToken");
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "비밀번호 변경", description = "Access JWT는 Authorization: Bearer 헤더 또는 accessToken 쿠키로 전달합니다. 둘 다 있으면 Bearer가 우선입니다. local 계정의 모든 Refresh family를 폐기한 뒤 비밀번호를 변경합니다. Redis 폐기 실패는 503이며 비밀번호 변경을 롤백합니다. 이미 발급한 Access는 만료까지 최대 5분 유효합니다. 기존 비밀번호를 먼저 검증합니다. 불일치는 PASSWORD_FAIL(400)이며 비밀번호와 Refresh 상태를 바꾸지 않습니다. 새 비밀번호는 공백 불가, 8~72자 및 UTF-8 최대 72바이트로 검증합니다. 먼저 GET /api/auth/csrf를 호출해 받은 쿠키를 유지하고 응답 token을 X-XSRF-TOKEN 헤더에 넣습니다. 같은 서버의 Swagger에서는 CSRF 조회 후 쿠키에서 토큰을 읽어 헤더에 자동 적용합니다. Bearer 인증에도 CSRF 헤더가 필요하며 Origin은 같은 서버 주소 또는 허용 목록의 주소여야 합니다.", security = @SecurityRequirement(name = "AccessCookie"))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "현재·새 비밀번호가 모두 필요합니다.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ChangePasswordRequest.class), examples = @ExampleObject(value = "{\"currentPassword\":\"Current-example-password-123!\",\"newPassword\":\"New-example-password-123!\"}")))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "비밀번호 변경 성공. 응답 본문 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 검증 실패·기존 비밀번호 불일치 또는 소셜 계정 비밀번호 변경 요청", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorEnvelope.class), examples = {@ExampleObject(name = "입력 검증 실패", value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}}"), @ExampleObject(name = "업무 규칙 실패", value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"INVALID_PROVIDER\",\"message\":\"잘못된 형식입니다. - 비밀번호 변경은 소셜로그인 대상자에게 제공하지 않습니다.\"}}") })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Security 필터 인증 실패. ApiResponse 래퍼 없이 반환", content = @Content(mediaType = "application/json", schema = @Schema(implementation = UnauthorizedSchema.class), examples = @ExampleObject(value = "{\"error\":\"Unauthorized\"}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 누락·불일치 또는 허용되지 않은 Origin. Security/서블릿 오류 응답은 ApiResponse JSON 계약이 없으며 컨테이너에 따라 본문이 달라집니다.", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "해당 사용자를 찾을 수 없습니다.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorEnvelope.class), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":502,\"code\":\"USER_NOT_FOUND\",\"message\":\"해당 사용자를 찾을 수 없습니다.\"}}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorEnvelope.class), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))
    })
    @PatchMapping("/password")
    public ResponseEntity<Void> changePassword(@Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
                                               @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(userDetails.getUserId(), request);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "닉네임 변경", description = "Access JWT는 Authorization: Bearer 헤더 또는 accessToken 쿠키로 전달합니다. 둘 다 있으면 Bearer가 우선입니다. 새 닉네임을 저장합니다. 닉네임은 공백 불가 및 2~50자로 검증하며 별도의 중복 확인은 적용하지 않습니다. 먼저 GET /api/auth/csrf를 호출해 받은 쿠키를 유지하고 응답 token을 X-XSRF-TOKEN 헤더에 넣습니다. 같은 서버의 Swagger에서는 CSRF 조회 후 쿠키에서 토큰을 읽어 헤더에 자동 적용합니다. Bearer 인증에도 CSRF 헤더가 필요하며 Origin은 같은 서버 주소 또는 허용 목록의 주소여야 합니다.", security = @SecurityRequirement(name = "AccessCookie"))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "JSON 요청 본문", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ChangeNicknameRequest.class), examples = @ExampleObject(value = "{\"newNickname\":\"새두더지\"}")))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 검증 실패 또는 누락·잘못된 JSON", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorEnvelope.class), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "닉네임 변경 성공. 응답 본문 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Security 필터 인증 실패. ApiResponse 래퍼 없이 반환", content = @Content(mediaType = "application/json", schema = @Schema(implementation = UnauthorizedSchema.class), examples = @ExampleObject(value = "{\"error\":\"Unauthorized\"}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 누락·불일치 또는 허용되지 않은 Origin. Security/서블릿 오류 응답은 ApiResponse JSON 계약이 없으며 컨테이너에 따라 본문이 달라집니다.", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "해당 사용자를 찾을 수 없습니다.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorEnvelope.class), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":502,\"code\":\"USER_NOT_FOUND\",\"message\":\"해당 사용자를 찾을 수 없습니다.\"}}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorEnvelope.class), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))
    })
    @PatchMapping("/nickname")
    public ResponseEntity<Void> changeNickname(@Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
                                               @Valid @RequestBody ChangeNicknameRequest request) {
        userService.changeNickname(userDetails.getUserId(), request);
        return ResponseEntity.ok().build();
    }

}
