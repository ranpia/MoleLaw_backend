package com.MoleLaw_backend.chat.dto.response;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "상담방 근거 목록 항목. 본문은 개별 근거 조회 API에서 반환합니다.")
public record EvidenceSummaryResponse(
        @Schema(description = "답변의 [[UUID]]와 동일한 근거 ID", example = "00000000-0000-0000-0000-000000000001") String id,
        @Schema(description = "근거 종류. CASE_METADATA는 판결 본문이 아닙니다.", example = "CASE_BODY") String kind,
        @Schema(description = "출처 내부 식별자. 판례는 판례일련번호", example = "12345") String sourceId,
        @Schema(description = "HTML 태그·엔티티를 표시용 일반 텍스트로 변환한 법령 조문명 또는 사건명", example = "손해배상(기)") String title,
        @Schema(description = "공식 원문 출처 URL. 기존 법령 링크는 lsInfoP.do?lsiSeq=MST 상세 페이지로 교정해 반환", example = "https://www.law.go.kr/precInfoP.do?precSeq=12345") String sourceUrl,
        @Schema(description = "법령 ID. 판례는 null", example = "001823", nullable = true) String lawId,
        @Schema(description = "원문 시행일 yyyyMMdd. 없는 경우 null", example = "20260101", nullable = true) String effectiveDate) {
    public static EvidenceSummaryResponse from(EvidenceItem item) {
        return new EvidenceSummaryResponse(item.id(), item.kind(), item.sourceId(), com.MoleLaw_backend.chat.util.EvidenceDisplayText.plain(item.title()),
                com.MoleLaw_backend.law.util.LawSourceUrl.publicLink(item.sourceUrl()),
                item.provenance() == null ? null : item.provenance().lawId(),
                item.provenance() == null ? null : item.provenance().effectiveDate());
    }
}
