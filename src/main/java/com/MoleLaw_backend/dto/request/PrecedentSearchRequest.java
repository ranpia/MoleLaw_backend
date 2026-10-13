package com.MoleLaw_backend.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PrecedentSearchRequest {
    @Schema(description = "법률 질문 또는 검색어", example = "퇴직금 미지급")
    private String query; // 예: "근로기준법", "제34조", "퇴직금", "해고"
}
