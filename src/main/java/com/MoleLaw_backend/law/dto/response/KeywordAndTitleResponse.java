package com.MoleLaw_backend.law.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class KeywordAndTitleResponse {
    @Schema(description = "추출한 법률 검색 키워드", example = "[\"퇴직금\", \"근로자퇴직급여 보장법\"]")
    private List<String> keywords;
    @Schema(description = "질문 요약", example = "퇴직금 미지급 대응 방법 문의")
    private String summary;
    @Schema(description = "관련 법령의 소관 부처", example = "고용노동부")
    private String ministry;
}
