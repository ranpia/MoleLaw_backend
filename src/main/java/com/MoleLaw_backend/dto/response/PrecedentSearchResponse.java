package com.MoleLaw_backend.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import com.MoleLaw_backend.dto.PrecedentInfo;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class PrecedentSearchResponse {

    @JsonProperty("PrecSearch")
    @Schema(description = "외부 판례 검색 결과")
    private PrecSearch precSearch;

    @Getter
    @Setter
    public static class PrecSearch {

        @JsonProperty("prec")
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) // ✅ 이 부분!
        @Schema(description = "검색된 판례 목록")
        private List<PrecedentInfo> prec;

        @JsonProperty("키워드")
        @Schema(description = "판례 검색 키워드", example = "퇴직금")
        private String keyword;

        @JsonProperty("page")
        @Schema(description = "검색 결과 페이지 번호", example = "1")
        private int page;

        @JsonProperty("section")
        @Schema(description = "외부 API의 검색 범위 구분", example = "prec")
        private String section;

        @JsonProperty("totalCnt")
        @Schema(description = "전체 검색 결과 수", example = "10")
        private int totalCnt;
    }
}
