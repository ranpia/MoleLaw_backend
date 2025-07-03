package com.MoleLaw_backend.dto.response;

import com.MoleLaw_backend.domain.entity.ChatRoom;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class ChatRoomResponse {
    private Long id;
    private String title;
    private String preview; // 🔹 첫 메시지 복호화
    private LocalDateTime createdAt;

    public static ChatRoomResponse from(ChatRoom chatRoom, String preview) {
        return ChatRoomResponse.builder()
                .id(chatRoom.getId())
                .title(chatRoom.getTitle())
                .preview(preview)
                .createdAt(chatRoom.getCreatedAt())
                .build();
    }

    public static ChatRoomResponse from(ChatRoom chatRoom) {
        return from(chatRoom, null);
    }
}
