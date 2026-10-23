package com.MoleLaw_backend.chat.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "공식 판례 원문에서 관측한 메타데이터. 원문에 없으면 null이며 모델이 보충하지 않습니다.")
public record CaseSourceResponse(
        @Schema(description = "사건번호", example = "2026다0001", nullable = true) String caseNumber,
        @Schema(description = "법원명", example = "대법원", nullable = true) String courtName,
        @Schema(description = "원문 선고일자", example = "20260101", nullable = true) String decisionDate,
        @Schema(description = "판시사항 원문", example = "판단 대상 쟁점", nullable = true) String issues,
        @Schema(description = "판결요지 원문", example = "판결 요지", nullable = true) String summary,
        @Schema(description = "참조조문 원문", example = "민법 제750조", nullable = true) String referenceLaws,
        @Schema(description = "참조판례 원문", example = "참조된 사건번호", nullable = true) String referenceCases) { }
