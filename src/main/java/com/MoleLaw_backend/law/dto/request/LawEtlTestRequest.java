package com.MoleLaw_backend.law.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@Schema(description = "로컬 법령 ETL 실행 조건. 페이지당 후보 1개로 최대 3페이지를 처리합니다.")
public record LawEtlTestRequest(
        @NotBlank @Size(max = 200) @Schema(description = "법령명 검색어", example = "민법") String query,
        @Min(1) @Max(10000) @Schema(description = "시작 페이지. API 상한 10000페이지에서 종료합니다.", example = "1") int startPage,
        @Min(1) @Max(3) @Schema(description = "처리할 최대 페이지 수", example = "3") int maxPages,
        @Valid @Schema(description = "생략 시 법령명 완전 일치. 날짜·법령 ID·종류·상태·정렬 조건을 지정할 수 있습니다.")
        LawSearchFilters filters) {
}
