package com.MoleLaw_backend.chat.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Getter;

@Getter
public class ChatRoomRequest {
    @Schema(description = "채팅방 제목", example = "퇴직금 상담")
    private String title;

    public ChatRoomRequest(String s) {
        this.title = s;
    }
}
