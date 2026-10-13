package com.MoleLaw_backend.chat.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Getter;

@Getter
public class MessageRequest {
    @Schema(description = "메시지 본문", example = "퇴직금을 받지 못했습니다.")
    private String content; // 사용자 질문
}
