package com.MoleLaw_backend.chat.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "최초·후속 상담 SSE/NDJSON 이벤트. 중간 info/delta는 근거 번호로 표시하며 completed는 원문 인용 검증을 마친 확정 결과입니다.")
public record InitialAnswerStreamResponse(
        @Schema(description = "이벤트 종류", example = "progress", allowableValues = {"progress", "info", "answer_delta", "completed", "error", "heartbeat"}) String type,
        @Schema(description = "실제 작업 단계. progress에서 제공", example = "INITIAL_SEARCH") String stage,
        @Schema(description = "작업 안내 또는 오류 안내", example = "관련 조문을 검색하고 있습니다.") String message,
        @Schema(description = "확인된 근거 전체 마크다운. 이전 info를 교체", example = "## 고정 근거\n\n") String info,
        @Schema(description = "검증 전 답변 조각. completed 전에는 임시 표시", example = "확인한 조문에 따르면 ") String delta,
        @Schema(description = "completed에서 제공하는 인용 검증 완료 답변") LegalAnswerResponse response,
        @Schema(description = "방 생성 스트림의 completed에서만 제공하는 저장 결과") ChatRoomCreationResponse room,
        @Schema(description = "error에서 제공하는 공개 오류 코드", example = "EVIDENCE_INSUFFICIENT") String code,
        @Schema(description = "후속 답변 completed에 제공하는 저장된 BOT 메시지. 동의 요청 여부 포함") MessageResponse messageResult) {
    public InitialAnswerStreamResponse(String type, String stage, String message, String info, String delta,
            LegalAnswerResponse response, ChatRoomCreationResponse room, String code) {
        this(type, stage, message, info, delta, response, room, code, null);
    }
}
