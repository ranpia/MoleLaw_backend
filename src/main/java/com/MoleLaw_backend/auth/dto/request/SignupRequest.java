package com.MoleLaw_backend.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Email;
import com.MoleLaw_backend.auth.validation.Utf8ByteLength;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SignupRequest {
    @NotBlank
    @Email
    @Size(max = 254)
    @Schema(description = "이메일 형식, 공백 불가, 최대 254자", example = "user@example.com", maxLength = 254, requiredMode = Schema.RequiredMode.REQUIRED)
    private String email;
    @NotBlank
    @Size(min = 8, max = 72)
    @Utf8ByteLength(max = 72)
    @Schema(description = "로컬 비밀번호, 공백 불가, 8~72자 및 UTF-8 최대 72바이트", example = "SamplePassword!234", minLength = 8, maxLength = 72, requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;
    @NotBlank
    @Size(min = 2, max = 50)
    @Schema(description = "닉네임, 공백 불가, 2~50자", example = "법률친구", minLength = 2, maxLength = 50, requiredMode = Schema.RequiredMode.REQUIRED)
    private String nickname;
}
