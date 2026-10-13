package com.MoleLaw_backend.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Getter;

@Getter
public class ChangeNicknameRequest {
    @Schema(description = "변경할 닉네임", example = "법률친구")
    private String newNickname;
}
