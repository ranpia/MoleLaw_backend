package com.MoleLaw_backend.law.dto.vector;

import io.swagger.v3.oas.annotations.media.Schema;

/** Completed or reusable source indexing result. */
public record LawIndexResult(
        @Schema(description = "실제 색인 원문 UUID. 완료 세대 재사용 시 최초 원문 UUID", example = "11111111-1111-1111-1111-111111111111") String referenceId,
        @Schema(description = "Qdrant 컬렉션", example = "molelaw_service_law_kure_v1") String collection,
        @Schema(description = "서로 다른 조문 수", example = "1") int articleCount,
        @Schema(description = "임베딩 청크 수", example = "2") int chunkCount) { }
