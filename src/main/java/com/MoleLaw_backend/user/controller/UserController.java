package com.MoleLaw_backend.user.controller;

import com.MoleLaw_backend.user.dto.request.ChangeNicknameRequest;
import com.MoleLaw_backend.user.dto.request.ChangePasswordRequest;
import com.MoleLaw_backend.auth.dto.request.LoginRequest;
import com.MoleLaw_backend.auth.dto.request.SignupRequest;
import com.MoleLaw_backend.common.dto.response.ApiResponse;
import com.MoleLaw_backend.auth.dto.response.AuthResponse;
import com.MoleLaw_backend.user.dto.response.UserResponse;
import com.MoleLaw_backend.chat.service.ChatService;
import com.MoleLaw_backend.auth.security.CustomUserDetails;
import com.MoleLaw_backend.user.service.UserService;
import com.MoleLaw_backend.auth.security.CookieUtil;
import com.MoleLaw_backend.auth.security.AuthTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "User", description = "회원가입·로그인·사용자 정보·쿠키 기반 토큰 재발급 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class UserController {

    private final ChatService chatService;
    private final UserService userService;
    private final CookieUtil cookieUtil;
    private final AuthTokenService authTokens;


    @Operation(summary = "회원가입", description = "local 계정을 생성하고 Access JWT·난수 Refresh의 HttpOnly 쿠키와 성공 JSON을 반환합니다. 화면 이동은 프론트에서 처리합니다. 현재 Bean Validation 검사는 적용하지 않습니다. 먼저 GET /api/auth/csrf를 호출해 받은 쿠키를 유지하고 응답 token을 X-XSRF-TOKEN 헤더에 넣습니다. 같은 서버의 Swagger에서는 CSRF 조회 후 쿠키에서 토큰을 읽어 헤더에 자동 적용합니다. Bearer 인증에도 CSRF 헤더가 필요하며 Origin은 같은 서버 주소 또는 허용 목록의 주소여야 합니다.")
    @SecurityRequirements
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "JSON 요청 본문", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SignupRequest.class), examples = @ExampleObject(value = "{\"email\":\"user@example.com\",\"password\":\"Example-password-123!\",\"nickname\":\"두더지\"}")))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "회원가입 성공. Access/Refresh 쿠키 발급 및 성공 JSON 반환", headers = @Header(name = "Set-Cookie", description = "accessToken: Path=/, 기본/운영 5분·local 개발 15시간; refreshToken: Path=/api/auth, family 절대 수명 최대 7일. HttpOnly 및 설정된 Secure/SameSite 적용", schema = @Schema(type = "string")), content = @Content(mediaType = "application/json", schema = @Schema(implementation = StringEnvelope.class), examples = @ExampleObject(value = "{\"success\":true,\"data\":\"회원가입 되었습니다.\"}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "이미 존재하는 이메일입니다.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorEnvelope.class), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"DUPLICATED_EMAIL\",\"message\":\"이미 존재하는 이메일입니다.\"}}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 누락·불일치 또는 허용되지 않은 Origin. Security/서블릿 오류 응답은 ApiResponse JSON 계약이 없으며 컨테이너에 따라 본문이 달라집니다.", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorEnvelope.class), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))
    })
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<String>> signup(@RequestBody SignupRequest request, HttpServletResponse response) {
        userService.signup(request, response);
        return ResponseEntity.ok(ApiResponse.success("회원가입 되었습니다."));
    }

    @Operation(summary = "로그인", description = "local 계정의 이메일과 비밀번호를 확인해 Access는 JSON과 HttpOnly 쿠키로, 난수 Refresh는 HttpOnly 쿠키로만 발급합니다. Refresh family의 절대 수명은 로그인부터 7일입니다. 소셜 로그인은 OAuth2 경로를 사용합니다. 먼저 GET /api/auth/csrf를 호출해 받은 쿠키를 유지하고 응답 token을 X-XSRF-TOKEN 헤더에 넣습니다. 같은 서버의 Swagger에서는 CSRF 조회 후 쿠키에서 토큰을 읽어 헤더에 자동 적용합니다. Bearer 인증에도 CSRF 헤더가 필요하며 Origin은 같은 서버 주소 또는 허용 목록의 주소여야 합니다.")
    @SecurityRequirements
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "JSON 요청 본문", content = @Content(mediaType = "application/json", schema = @Schema(implementation = LoginRequest.class), examples = @ExampleObject(value = "{\"email\":\"user@example.com\",\"password\":\"Example-password-123!\"}")))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공", headers = @Header(name = "Set-Cookie", description = "accessToken: Path=/, 기본/운영 5분·local 15시간. refreshToken: Path=/api/auth, 최초 로그인부터 최대 7일의 남은 수명. 둘 다 HttpOnly 및 설정된 Secure/SameSite 적용", schema = @Schema(type = "string")), content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthResponse.class), examples = @ExampleObject(value = "{\"accessToken\":\"<access-jwt>\"}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "비밀번호가 일치하지 않습니다.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorEnvelope.class), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"PASSWORD_FAIL\",\"message\":\"비밀번호가 일치하지 않습니다.\"}}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "해당 사용자를 찾을 수 없습니다. - 해당 이메일이 존재하지 않습니다.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorEnvelope.class), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":502,\"code\":\"USER_NOT_FOUND\",\"message\":\"해당 사용자를 찾을 수 없습니다. - 해당 이메일이 존재하지 않습니다.\"}}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 누락·불일치 또는 허용되지 않은 Origin. Security/서블릿 오류 응답은 ApiResponse JSON 계약이 없으며 컨테이너에 따라 본문이 달라집니다.", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorEnvelope.class), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))
    })
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request, HttpServletResponse response) {
        AuthResponse authResponse = userService.login(request, response);
        return ResponseEntity.ok(authResponse);
    }

    @Operation(summary = "내 정보 조회", description = "Access JWT는 Authorization: Bearer 헤더 또는 accessToken 쿠키로 전달합니다. 둘 다 있으면 Bearer가 우선입니다. JWT subject의 User.id로 현재 사용자의 이메일과 닉네임을 조회합니다. 구형 이메일 subject 토큰은 401로 거절합니다.", security = @SecurityRequirement(name = "AccessCookie"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공", content = @Content(mediaType = "application/json", schema = @Schema(implementation = UserEnvelope.class), examples = @ExampleObject(value = "{\"success\":true,\"data\":{\"email\":\"user@example.com\",\"nickname\":\"두더지\"}}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Security 필터 인증 실패. ApiResponse 래퍼 없이 반환", content = @Content(mediaType = "application/json", schema = @Schema(implementation = UnauthorizedSchema.class), examples = @ExampleObject(value = "{\"error\":\"Unauthorized\"}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "해당 사용자를 찾을 수 없습니다.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorEnvelope.class), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":502,\"code\":\"USER_NOT_FOUND\",\"message\":\"해당 사용자를 찾을 수 없습니다.\"}}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorEnvelope.class), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))
    })
    @GetMapping("/me")
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
    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteUser(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            HttpServletResponse response) {
        userService.deleteUser(userDetails.getUserId());
        cookieUtil.clearJwtCookie(response, "accessToken");
        cookieUtil.clearJwtCookie(response, "refreshToken");
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "로그아웃", description = "인증 없이 호출할 수 있으며 전달된 Access의 Redis 등록을 폐기한 뒤 두 JWT 쿠키를 삭제합니다. Bearer와 Access 쿠키가 다르면 둘 다 폐기합니다. 함께 전달된 Refresh 쿠키의 family를 폐기해 회전 후 토큰도 재발급할 수 없게 합니다. 이미 소비한 Refresh 쿠키로 로그아웃해도 같은 family를 폐기합니다. 다른 로그인 family와 제시하지 않은 Access는 유지합니다. Redis 폐기 실패는 503이며 성공으로 처리하지 않습니다. 요청 본문은 필요하지 않습니다. 먼저 GET /api/auth/csrf를 호출해 받은 쿠키를 유지하고 응답 token을 X-XSRF-TOKEN 헤더에 넣습니다. 같은 서버의 Swagger에서는 CSRF 조회 후 쿠키에서 토큰을 읽어 헤더에 자동 적용합니다. Bearer 인증에도 CSRF 헤더가 필요하며 Origin은 같은 서버 주소 또는 허용 목록의 주소여야 합니다.")
    @SecurityRequirements
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "전달된 유효 Access 폐기 및 쿠키 삭제 성공", headers = @Header(name = "Set-Cookie", description = "accessToken Path=/ 및 refreshToken Path=/api/auth 삭제. 이전 Path=/ Refresh도 삭제", schema = @Schema(type = "string")), content = @Content(mediaType = "application/json", schema = @Schema(implementation = StringEnvelope.class), examples = @ExampleObject(value = "{\"success\":true,\"data\":\"로그아웃 되었습니다.\"}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 누락·불일치 또는 허용되지 않은 Origin. Security/서블릿 오류 응답은 ApiResponse JSON 계약이 없으며 컨테이너에 따라 본문이 달라집니다.", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorEnvelope.class), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))
    })
    @PostMapping(value = "/logout", consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<String>> logout(HttpServletRequest request, HttpServletResponse response) {
        authTokens.logout(request, response);
        return ResponseEntity.ok(ApiResponse.success("로그아웃 되었습니다."));
    }

    @Operation(summary = "액세스 토큰 재발급", description = "Refresh 쿠키의 해시·세대·family·절대 만료와 DB 사용자 존재를 확인해 기존 Refresh를 소비하고 새 난수 Refresh와 Access를 발급합니다. Access 인증은 필요하지 않습니다. Refresh는 HttpOnly Set-Cookie로만 전달하며 JSON에는 Access만 포함됩니다. family 수명은 최초 로그인부터 7일이고 회전으로 연장하지 않습니다. 이전 Refresh 재사용은 family 전체 폐기와 401이며 응답 유실·다중 탭 재시도도 동일합니다. 요청 본문은 필요하지 않습니다. 먼저 GET /api/auth/csrf를 호출해 받은 쿠키를 유지하고 응답 token을 X-XSRF-TOKEN 헤더에 넣습니다. 같은 서버의 Swagger에서는 CSRF 조회 후 쿠키에서 토큰을 읽어 헤더에 자동 적용합니다. Bearer 인증에도 CSRF 헤더가 필요하며 Origin은 같은 서버 주소 또는 허용 목록의 주소여야 합니다.")
    @SecurityRequirements
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Access 발급 및 accessToken/refreshToken 쿠키 갱신", headers = @Header(name = "Set-Cookie", description = "accessToken: Path=/, 기본/운영 5분·local 15시간. refreshToken: Path=/api/auth, 최초 로그인부터 최대 7일의 남은 수명. 둘 다 HttpOnly 및 설정된 Secure/SameSite 적용", schema = @Schema(type = "string")), content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthEnvelope.class), examples = @ExampleObject(value = "{\"success\":true,\"data\":{\"accessToken\":\"<new-access-jwt>\"}}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Refresh 누락·구형 JWT·만료·미등록·폐기 또는 재사용. 재사용 시 해당 family 전체 폐기", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorEnvelope.class), examples = {
                    @ExampleObject(name = "유효하지 않은 Refresh", value = "{\"success\":false,\"error\":{\"status\":401,\"code\":\"INVALID_REFRESH_TOKEN\",\"message\":\"유효하지 않거나 만료·폐기된 Refresh 토큰입니다.\"}}"),
                    @ExampleObject(name = "재사용 탐지", value = "{\"success\":false,\"error\":{\"status\":401,\"code\":\"REFRESH_TOKEN_REUSED\",\"message\":\"Refresh 토큰 재사용이 감지되어 로그인 세션이 폐기되었습니다.\"}}") })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 누락·불일치 또는 허용되지 않은 Origin. Security/서블릿 오류 응답은 ApiResponse JSON 계약이 없으며 컨테이너에 따라 본문이 달라집니다.", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorEnvelope.class), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))
    })
    @PostMapping("/reissue")
    public ResponseEntity<ApiResponse<AuthResponse>> reissue(HttpServletRequest request, HttpServletResponse response) {
        AuthResponse authResponse = userService.reissue(request, response);
        return ResponseEntity.ok(ApiResponse.success(authResponse));
    }

    @Operation(summary = "비밀번호 변경", description = "Access JWT는 Authorization: Bearer 헤더 또는 accessToken 쿠키로 전달합니다. 둘 다 있으면 Bearer가 우선입니다. local 계정의 모든 Refresh family를 폐기한 뒤 비밀번호를 변경합니다. Redis 폐기 실패는 503이며 비밀번호 변경을 롤백합니다. 이미 발급한 Access는 기존 수명 정책을 따릅니다. 현재 비밀번호 확인이나 새 비밀번호의 Bean Validation은 적용하지 않습니다. 먼저 GET /api/auth/csrf를 호출해 받은 쿠키를 유지하고 응답 token을 X-XSRF-TOKEN 헤더에 넣습니다. 같은 서버의 Swagger에서는 CSRF 조회 후 쿠키에서 토큰을 읽어 헤더에 자동 적용합니다. Bearer 인증에도 CSRF 헤더가 필요하며 Origin은 같은 서버 주소 또는 허용 목록의 주소여야 합니다.", security = @SecurityRequirement(name = "AccessCookie"))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "JSON 요청 본문", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ChangePasswordRequest.class), examples = @ExampleObject(value = "{\"newPassword\":\"New-example-password-123!\"}")))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "비밀번호 변경 성공. 응답 본문 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 형식입니다. - 비밀번호 변경은 소셜로그인 대상자에게 제공하지 않습니다.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorEnvelope.class), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"INVALID_PROVIDER\",\"message\":\"잘못된 형식입니다. - 비밀번호 변경은 소셜로그인 대상자에게 제공하지 않습니다.\"}}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Security 필터 인증 실패. ApiResponse 래퍼 없이 반환", content = @Content(mediaType = "application/json", schema = @Schema(implementation = UnauthorizedSchema.class), examples = @ExampleObject(value = "{\"error\":\"Unauthorized\"}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 누락·불일치 또는 허용되지 않은 Origin. Security/서블릿 오류 응답은 ApiResponse JSON 계약이 없으며 컨테이너에 따라 본문이 달라집니다.", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "해당 사용자를 찾을 수 없습니다.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorEnvelope.class), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":502,\"code\":\"USER_NOT_FOUND\",\"message\":\"해당 사용자를 찾을 수 없습니다.\"}}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorEnvelope.class), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))
    })
    @PatchMapping("/password")
    public ResponseEntity<Void> changePassword(@Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
                                               @RequestBody ChangePasswordRequest request) {
        userService.changePassword(userDetails.getUserId(), request);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "닉네임 변경", description = "Access JWT는 Authorization: Bearer 헤더 또는 accessToken 쿠키로 전달합니다. 둘 다 있으면 Bearer가 우선입니다. 새 닉네임을 저장합니다. 현재 별도의 중복 확인이나 Bean Validation 검사는 적용하지 않습니다. 먼저 GET /api/auth/csrf를 호출해 받은 쿠키를 유지하고 응답 token을 X-XSRF-TOKEN 헤더에 넣습니다. 같은 서버의 Swagger에서는 CSRF 조회 후 쿠키에서 토큰을 읽어 헤더에 자동 적용합니다. Bearer 인증에도 CSRF 헤더가 필요하며 Origin은 같은 서버 주소 또는 허용 목록의 주소여야 합니다.", security = @SecurityRequirement(name = "AccessCookie"))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "JSON 요청 본문", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ChangeNicknameRequest.class), examples = @ExampleObject(value = "{\"newNickname\":\"새두더지\"}")))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "닉네임 변경 성공. 응답 본문 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Security 필터 인증 실패. ApiResponse 래퍼 없이 반환", content = @Content(mediaType = "application/json", schema = @Schema(implementation = UnauthorizedSchema.class), examples = @ExampleObject(value = "{\"error\":\"Unauthorized\"}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 누락·불일치 또는 허용되지 않은 Origin. Security/서블릿 오류 응답은 ApiResponse JSON 계약이 없으며 컨테이너에 따라 본문이 달라집니다.", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "해당 사용자를 찾을 수 없습니다.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorEnvelope.class), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":502,\"code\":\"USER_NOT_FOUND\",\"message\":\"해당 사용자를 찾을 수 없습니다.\"}}"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorEnvelope.class), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))
    })
    @PatchMapping("/nickname")
    public ResponseEntity<Void> changeNickname(@Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
                                               @RequestBody ChangeNicknameRequest request) {
        userService.changeNickname(userDetails.getUserId(), request);
        return ResponseEntity.ok().build();
    }
    @Schema(name = "AuthUserEnvelope")
    public static class UserEnvelope extends ApiResponse<UserResponse> {}
    @Schema(name = "AuthTokenEnvelope")
    public static class AuthEnvelope extends ApiResponse<AuthResponse> {}
    @Schema(name = "AuthStringEnvelope")
    public static class StringEnvelope extends ApiResponse<String> {}
    @Schema(name = "AuthErrorEnvelope")
    public static class ErrorEnvelope extends ApiResponse<Void> {}
    @Schema(name = "AuthUnauthorized")
    public record UnauthorizedSchema(@Schema(description = "Security 인증 실패 메시지", example = "Unauthorized") String error) {}
}
