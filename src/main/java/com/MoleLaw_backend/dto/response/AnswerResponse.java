package com.MoleLaw_backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AnswerResponse {
    private String answer; // GPT가 생성한 답변
    private String info;   // 마크다운 형식의 법령/판례 정리
}
