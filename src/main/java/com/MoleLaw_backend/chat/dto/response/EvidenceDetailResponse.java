package com.MoleLaw_backend.chat.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "방에 확정된 불변 근거 본문. 수집·임베딩을 다시 실행하지 않습니다.")
public record EvidenceDetailResponse(
        @Schema(description = "근거 식별자와 출처") EvidenceSummaryResponse evidence,
        @Schema(description = "법령은 전체 조문 텍스트, CASE_BODY는 판례내용 원문. 공급자 HTML 표기가 있을 수 있으므로 HTML로 직접 렌더링하지 마세요.", example = "판결 본문") String content,
        @Schema(description = "판례 본문 근거의 추가 출처 정보. 법령은 null", nullable = true) CaseSourceResponse caseInfo) { }
