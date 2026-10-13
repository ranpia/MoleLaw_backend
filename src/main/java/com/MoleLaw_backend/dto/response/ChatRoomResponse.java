package com.MoleLaw_backend.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import com.MoleLaw_backend.domain.entity.ChatRoom;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class ChatRoomResponse {
    @Schema(description = "채팅방 식별자", example = "1")
    private Long id;
    @Schema(description = "채팅방 제목", example = "퇴직금 상담")
    private String title;
    @Schema(description = "복호화된 첫 메시지 미리보기", example = "퇴직금을 받지 못했습니다.")
    private String preview; // 🔹 첫 메시지 복호화
    @Schema(description = "채팅방 생성 시각", example = "2026-01-15T10:30:00")
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
