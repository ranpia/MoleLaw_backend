package com.MoleLaw_backend.dto.request;

import lombok.Getter;

@Getter
public class ChatRoomRequest {
    private String title;

    public ChatRoomRequest(String s) {
        this.title = s;
    }
}
