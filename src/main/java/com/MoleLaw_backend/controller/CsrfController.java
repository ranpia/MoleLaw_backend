package com.MoleLaw_backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Tag(name = "CSRF", description = "쿠키 기반 상태 변경 요청에 필요한 CSRF 토큰 조회")
public class CsrfController {
    @Operation(summary = "CSRF 토큰 조회", description = "인증 없이 호출할 수 있습니다. 응답 token을 이후 POST/PUT/PATCH/DELETE 요청의 X-XSRF-TOKEN 헤더에 넣고 이 호출에서 받은 XSRF-TOKEN 쿠키도 함께 전송합니다. Bearer 인증에도 CSRF 헤더가 필요합니다. 쿠키는 HttpOnly이므로 JavaScript는 응답 JSON에서 token을 읽어야 합니다. 브라우저의 교차 Origin 요청은 credentials를 포함하고 허용된 Origin에서 호출해야 합니다. 응답은 Cache-Control: no-store입니다.")
    @SecurityRequirements
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "CSRF 토큰 및 전송 헤더 이름", headers = {
                    @Header(name = "Cache-Control", schema = @Schema(type = "string", example = "no-store")),
                    @Header(name = "Set-Cookie", description = "XSRF-TOKEN 쿠키. Path=/, HttpOnly, 설정된 Secure/SameSite 적용", schema = @Schema(type = "string"))
            }, content = @Content(mediaType = "application/json", schema = @Schema(implementation = CsrfResponseSchema.class), examples = @ExampleObject(value = "{\"token\":\"example-csrf-token\",\"headerName\":\"X-XSRF-TOKEN\"}"))),
            @ApiResponse(responseCode = "403", description = "허용되지 않은 Origin의 CORS 요청. CORS 필터의 텍스트 응답이며 ApiResponse 형식이 아닙니다.", content = @Content(mediaType = "text/plain", schema = @Schema(type = "string"), examples = @ExampleObject(value = "Invalid CORS request")))
    })
    @GetMapping("/api/auth/csrf")
    public ResponseEntity<Map<String, String>> csrf(@Parameter(hidden = true) CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("token", token.getToken(), "headerName", token.getHeaderName()));
    }

    @Schema(name = "CsrfResponse")
    public record CsrfResponseSchema(
            @Schema(description = "상태 변경 요청의 X-XSRF-TOKEN 헤더에 넣을 토큰", example = "example-csrf-token") String token,
            @Schema(description = "CSRF 토큰을 전달할 헤더 이름", example = "X-XSRF-TOKEN") String headerName) {}
}
