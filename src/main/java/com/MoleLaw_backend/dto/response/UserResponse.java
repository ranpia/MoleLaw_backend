package com.MoleLaw_backend.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import com.MoleLaw_backend.domain.entity.User;
import lombok.Getter;

@Getter
public class UserResponse {
    @Schema(description = "사용자 이메일", example = "user@example.com")
    private String email;
    @Schema(description = "사용자 닉네임", example = "법률친구")
    private String nickname;

    public UserResponse(User user) {
        this.email = user.getEmail();
        this.nickname = user.getNickname();
    }
}
