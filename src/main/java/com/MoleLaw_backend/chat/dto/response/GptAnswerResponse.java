package com.MoleLaw_backend.chat.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class GptAnswerResponse {
    @Schema(description = "AI가 생성한 답변", example = "퇴직금 지급 요건을 확인해야 합니다.")
    private String answer; // GPT가 생성한 답변
    @Schema(description = "마크다운 형식의 법령 및 판례 참고 정보", example = "## 관련 법령\n근로자퇴직급여 보장법")
    private String info;   // 마크다운 형식의 법령/판례 정리
}
