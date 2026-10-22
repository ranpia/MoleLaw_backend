package com.MoleLaw_backend.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Email;
import com.MoleLaw_backend.auth.validation.Utf8ByteLength;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class LoginRequest {
    @NotBlank
    @Email
    @Size(max = 254)
    @Schema(description = "이메일 형식, 공백 불가, 최대 254자", example = "user@example.com", maxLength = 254, requiredMode = Schema.RequiredMode.REQUIRED)
    private String email;
    @NotBlank
    @Size(min = 1, max = 72)
    @Utf8ByteLength(max = 72)
    @Schema(description = "로컬 비밀번호, 공백 불가, 1~72자 및 UTF-8 최대 72바이트", example = "SamplePassword!234", minLength = 1, maxLength = 72, requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;
}
