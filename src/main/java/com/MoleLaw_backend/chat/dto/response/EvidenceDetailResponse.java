package com.MoleLaw_backend.chat.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "방에 확정된 불변 근거 본문. 수집·임베딩을 다시 실행하지 않습니다.")
public record EvidenceDetailResponse(
        @Schema(description = "근거 식별자와 출처") EvidenceSummaryResponse evidence,
        @Schema(description = "보존된 원문의 표시용 일반 텍스트. HTML 태그를 제거하고 엔티티·문단·줄바꿈을 변환합니다. 원문 저장값과 출처 ID는 변경하지 않습니다.", example = "주문\n원심판결을 파기한다.\n이유\n판결 이유") String content,
        @Schema(description = "판례 본문 근거의 추가 출처 정보. 법령은 null", nullable = true) CaseSourceResponse caseInfo) { }
