package com.MoleLaw_backend.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Getter;

@Getter
public class ChangePasswordRequest {
    @Schema(description = "새 비밀번호 (로컬 계정 전용)", example = "SamplePassword!234")
    private String newPassword;
}
