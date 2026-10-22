package com.MoleLaw_backend.law.dto.response;

import com.MoleLaw_backend.law.dto.vector.LawSearchEvidence;
import io.swagger.v3.oas.annotations.media.Schema;

public record LawRagTestResponse(
        @Schema(description = "MST와 조문 경로", example = "123:1") String unitId,
        @Schema(description = "저장 원문 UUID", example = "11111111-1111-1111-1111-111111111111") String referenceId,
        @Schema(description = "법령 버전 MST", example = "123") String externalId,
        @Schema(description = "법령 ID", example = "001823") String lawId,
        @Schema(description = "시행일. 미제공 시 빈 문자열", example = "20260101") String effectiveDate,
        @Schema(description = "법령명과 조문 제목", example = "민법 제1조(법원)") String title,
        @Schema(description = "매칭된 청크 본문", example = "민사에 관하여 법률에 규정이 없으면 관습법에 의하고") String text,
        @Schema(description = "답변 근거로 사용할 전체 조문", example = "민사에 관하여 법률에 규정이 없으면 관습법에 의하고 관습법이 없으면 조리에 의한다.") String articleText,
        @Schema(description = "공식 법령 링크", example = "https://www.law.go.kr/법령?lsId=001823&lsiSeq=123") String sourceUrl,
        @Schema(description = "코사인 유사도. 법률적 정확도 확률이 아닙니다.", example = "0.82") double score,
        @Schema(description = "임베딩 모델 고정 리비전", example = "revision-1") String modelRevision,
        @Schema(description = "모델·청킹·컬렉션 설정 해시", example = "0123456789abcdef") String configFingerprint,
        @Schema(description = "공개 완료 색인 세대 UUID", example = "22222222-2222-2222-2222-222222222222") String generation) {
    public static LawRagTestResponse from(LawSearchEvidence hit) {
        return new LawRagTestResponse(hit.unitId(), hit.referenceId(), hit.externalId(), hit.lawId(),
                hit.effectiveDate(), hit.title(), hit.text(), hit.articleText(), hit.sourceUrl(), hit.score(),
                hit.modelRevision(), hit.configFingerprint(), hit.generation());
    }
}
