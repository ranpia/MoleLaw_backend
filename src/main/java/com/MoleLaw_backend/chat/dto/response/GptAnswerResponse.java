package com.MoleLaw_backend.chat.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class GptAnswerResponse {
    @Schema(description = "서버 근거 ID 인용을 검증한 AI 답변", example = "퇴직금 지급 요건을 확인해야 합니다. [[00000000-0000-0000-0000-000000000001]]")
    private String answer; // GPT가 생성한 답변
    @Schema(description = "서버가 생성한 고정 근거 ID·출처·본문 마크다운", example = "## 고정 근거\n- [[00000000-0000-0000-0000-000000000001]] [근로자퇴직급여 보장법](https://www.law.go.kr/법령/근로자퇴직급여보장법)")
    private String info;   // 마크다운 형식의 법령/판례 정리
}
