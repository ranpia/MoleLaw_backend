package com.MoleLaw_backend.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Getter;

@Getter
public class ChangeNicknameRequest {
    @NotBlank
    @Size(min = 2, max = 50)
    @Schema(description = "닉네임, 공백 불가, 2~50자", example = "법률친구", minLength = 2, maxLength = 50, requiredMode = Schema.RequiredMode.REQUIRED)
    private String newNickname;
}
