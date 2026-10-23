package com.MoleLaw_backend.law.dto.response;

import com.MoleLaw_backend.law.dto.retrieval.RetrievalBackend;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "동일 검색어의 조문 API/Qdrant 비교. 관련성 정답 평가나 전체 법령 품질 점수가 아닙니다.")
public record LawRetrievalComparisonResponse(
        @Schema(description = "양쪽에 그대로 전달한 검색어", example = "공항 높이 제한") String query,
        @Schema(description = "원격 조문 검색 결과") BackendResult aiSearch,
        @Schema(description = "현재 공개 완료 색인의 벡터 검색 결과") BackendResult qdrant,
        @Schema(description = "두 결과에 공통으로 존재한 법령 ID. 정확도 지표가 아닙니다.", example = "[\"001234\"]") List<String> commonLawIds) {
    public enum Status { AVAILABLE, EMPTY, UNAVAILABLE, BUSY }

    @Schema(description = "검색별 상태·시간·순위. 장애는 빈 결과와 구분하며 다른 경로로 대체하지 않습니다.")
    public record BackendResult(
            @Schema(description = "검색 경로", example = "AI_SEARCH") RetrievalBackend backend,
            @Schema(description = "AVAILABLE/EMPTY/UNAVAILABLE/BUSY", example = "AVAILABLE") Status status,
            @Schema(description = "해당 검색 경로의 실제 경과 밀리초", example = "345") long elapsedMillis,
            @Schema(description = "반환 조문 수", example = "1") int returnedCount,
            @Schema(description = "제공자가 보고한 전체 건수. Qdrant·장애 시 null", nullable = true, example = "25") Integer providerTotalCount,
            @Schema(description = "API 다음 페이지 존재 여부. Qdrant·장애 시 null", nullable = true, example = "true") Boolean hasNext,
            @Schema(description = "장애 분류 코드. 정상 결과는 null", nullable = true, example = "API_FAILURE") String errorCode,
            @Schema(description = "해당 제공자의 순서대로 반환한 조문") List<Hit> hits) { }

    public record Hit(
            @Schema(description = "제공자 내부 검색 순위. 경로 간 동일 척도가 아닙니다.", example = "1") int rank,
            @Schema(description = "법령·조문 제목", example = "검증법 제1조(목적)") String title,
            @Schema(description = "법령 ID", example = "001234") String lawId,
            @Schema(description = "법령 일련번호", example = "123456") String mst,
            @Schema(description = "응답에 표시된 시행일. 현재 시행 중임을 보장하지 않습니다.", nullable = true, example = "20260101") String effectiveDate,
            @Schema(description = "API 조문 ID 또는 Qdrant 조문 경로", example = "1234567") String articleIdentity,
            @Schema(description = "검색된 조문 본문", example = "이 법은 검증을 목적으로 한다.") String content,
            @Schema(description = "공식 법령 출처 링크", example = "https://www.law.go.kr/법령?lsiSeq=123456") String sourceUrl) { }
}
