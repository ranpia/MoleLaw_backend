package com.MoleLaw_backend.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Getter;

@Getter
public class FirstMessageRequest {
    @Schema(description = "메시지 본문", example = "퇴직금을 받지 못했습니다.")
    private String content;
}
