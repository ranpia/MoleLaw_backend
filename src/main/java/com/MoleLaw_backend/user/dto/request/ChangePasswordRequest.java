package com.MoleLaw_backend.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.MoleLaw_backend.auth.validation.Utf8ByteLength;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Getter;

@Getter
public class ChangePasswordRequest {
    @NotBlank
    @Size(min = 1, max = 72)
    @Utf8ByteLength(max = 72)
    @Schema(description = "현재 비밀번호. 기존 로그인 비밀번호와 일치해야 하며 공백을 임의 제거하지 않습니다. UTF-8 최대 72바이트", example = "CurrentPassword!234", minLength = 1, maxLength = 72, requiredMode = Schema.RequiredMode.REQUIRED)
    private String currentPassword;

    @NotBlank
    @Size(min = 8, max = 72)
    @Utf8ByteLength(max = 72)
    @Schema(description = "로컬 비밀번호, 공백 불가, 8~72자 및 UTF-8 최대 72바이트", example = "SamplePassword!234", minLength = 8, maxLength = 72, requiredMode = Schema.RequiredMode.REQUIRED)
    private String newPassword;
}
