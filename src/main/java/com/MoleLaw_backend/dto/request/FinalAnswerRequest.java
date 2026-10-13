package com.MoleLaw_backend.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;


import com.MoleLaw_backend.dto.response.KeywordAndTitleResponse;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class FinalAnswerRequest {
    @Schema(description = "법률 질문 또는 검색어", example = "퇴직금 미지급")
    private String query;
    @Schema(description = "추출한 키워드와 질문 요약")
    private KeywordAndTitleResponse keywordInfo;
}
