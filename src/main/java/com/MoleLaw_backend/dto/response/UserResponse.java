package com.MoleLaw_backend.dto.response;

import com.MoleLaw_backend.domain.entity.User;
import lombok.Getter;

@Getter
public class UserResponse {
    private String email;
    private String nickname;

    public UserResponse(User user) {
        this.email = user.getEmail();
        this.nickname = user.getNickname();
    }
}
