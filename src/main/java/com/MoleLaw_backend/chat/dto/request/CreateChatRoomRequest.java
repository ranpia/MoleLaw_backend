package com.MoleLaw_backend.chat.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Getter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Getter
@Schema(name = "FirstMessageRequest")
public class CreateChatRoomRequest {
    @NotBlank
    @Size(max = 4000)
    @Schema(description = "공백이 아닌 질문. Unicode 최대 2000자, UTF-8 최대 32KiB. 서버에서 코드포인트·byte 수를 검증합니다.", example = "퇴직금을 받지 못했습니다.", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 2000)
    private String content;
}
