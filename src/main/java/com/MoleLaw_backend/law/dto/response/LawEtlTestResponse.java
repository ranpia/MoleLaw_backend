package com.MoleLaw_backend.law.dto.response;

import com.MoleLaw_backend.law.dto.vector.LawIndexResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record LawEtlTestResponse(
        @Schema(description = "서버 실행 ID", example = "etl-1") String executionId,
        @Schema(description = "조회한 페이지 수", example = "1") int pagesVisited,
        @Schema(description = "다음 요청의 startPage. 검색 종료 또는 API 10000페이지 상한이면 null", example = "4", nullable = true) Integer nextPage,
        @Schema(description = "완료되거나 재사용한 색인 목록. 조회 결과가 없으면 빈 배열")
        List<LawIndexResult> indexes) {
    public LawEtlTestResponse { indexes = List.copyOf(indexes); }
}
