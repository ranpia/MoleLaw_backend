package com.MoleLaw_backend.chat.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Getter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Getter
@Schema(name = "MessageRequest")
public class CreateMessageRequest {
    @NotBlank
    @Size(max = 4000)
    @Schema(description = "공백이 아닌 질문. Unicode 최대 2000자, UTF-8 최대 32KiB. 서버에서 코드포인트·byte 수를 검증합니다.", example = "퇴직금을 받지 못했습니다.", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 2000)
    private String content; // 사용자 질문

    @Schema(description = "새 판례의 외부 검색·본문 수집에 대한 이번 요청의 명시적 사용자 동의. 생략·false이면 외부 판례 도구는 차단되고 봇이 먼저 동의를 묻습니다. 기존 방 판례의 로컬 읽기는 허용합니다. UI의 승인 동작에서만 true로 보내세요.", example = "false", defaultValue = "false")
    private boolean allowCaseSearch;
}
