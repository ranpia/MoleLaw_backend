package com.MoleLaw_backend.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Schema(description = "Access 응답. Refresh는 JSON에 포함하지 않고 HttpOnly Set-Cookie로만 전달합니다.")
public class AuthResponse {
    @Schema(description = "API 인증용 Access JWT. 실제 토큰이 아닌 예시입니다.", example = "<access-token>")
    private String accessToken;
}
