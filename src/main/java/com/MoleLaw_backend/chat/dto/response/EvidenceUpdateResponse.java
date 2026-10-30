package com.MoleLaw_backend.chat.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "실시간 근거 목록 전체 스냅샷. id로 중복을 제거하고 이전 실시간 목록을 items로 교체합니다. PREVIEW는 저장 전 미리보기이며 오류 시 폐기하고 기존 저장 목록을 복원합니다. SAVED는 답변과 근거 저장이 성공한 후의 실제 방 목록입니다.")
public record EvidenceUpdateResponse(
        @Schema(description = "기존 방 또는 저장 완료된 새 방 ID. 최초 상담의 저장 전 미리보기에서는 null", types = {"integer", "null"}, format = "int64", example = "16") Long roomId,
        @Schema(description = "PREVIEW: 읽고 확인한 근거의 임시 목록. SAVED: 답변 성공 후 실제 저장 목록. PREVIEW의 새 근거는 개별 근거 조회 API에 아직 없을 수 있습니다.", example = "PREVIEW") State state,
        @Schema(description = "현재 목록 전체. 본문·원시 JSON은 포함하지 않습니다. 빈 배열이면 미리보기 목록을 비웁니다.") List<EvidenceSummaryResponse> items) {
    public enum State { PREVIEW, SAVED }
    public EvidenceUpdateResponse { items = List.copyOf(items); }
}
