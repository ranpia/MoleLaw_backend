package com.MoleLaw_backend.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class LoginRequest {
    @Schema(description = "사용자 이메일", example = "user@example.com")
    private String email;
    @Schema(description = "로컬 계정 비밀번호", example = "SamplePassword!234")
    private String password;
}
