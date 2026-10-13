package com.MoleLaw_backend.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AuthResponse {
    @Schema(description = "API 인증용 Access JWT. 실제 토큰이 아닌 예시입니다.", example = "<access-token>")
    private String accessToken;
    @Schema(description = "재발급용 Refresh JWT. 실제 토큰이 아닌 예시입니다.", example = "<refresh-token>")
    private String refreshToken;
}
