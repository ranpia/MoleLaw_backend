package com.MoleLaw_backend.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class FirstMessageResponse {
    @Schema(description = "채팅방 식별자", example = "1")
    private Long id;
    @Schema(description = "채팅 메시지 목록")
    private List<MessageResponse> messages;
}
