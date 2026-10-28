package com.MoleLaw_backend.chat.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Getter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Getter
@Schema(name = "MessageRequest")
public class CreateMessageRequest {
    @NotBlank
    @Size(max = 4000)
    @Schema(description = "공백이 아닌 질문. Unicode 최대 2000자, UTF-8 최대 32KiB. 서버에서 코드포인트·byte 수를 검증합니다.", example = "퇴직금을 받지 못했습니다.", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 2000)
    private String content; // 사용자 질문

    @Schema(description = "새 판례의 외부 검색·본문 수집에 대한 이번 요청의 명시적 사용자 동의. 생략·false이면 외부 판례 도구는 차단되고 봇이 먼저 동의를 묻습니다. 기존 방 판례의 로컬 읽기는 허용합니다. UI의 승인 동작에서만 true로 보내세요.", example = "false", defaultValue = "false")
    private boolean allowCaseSearch;


    @Schema(description = "사용자가 선택한 판례 검색 맥락의 법령 근거 UUID 목록. 개수 상한이 없습니다. 생략/null/[]이면 법령을 자동 선택하지 않고 원래 검색 요청만으로 검색합니다. GET /api/chat-rooms/{roomId}/evidence에서 kind가 LAW_로 시작하는 항목의 id를 보내세요. 비어 있지 않은 목록은 allowCaseSearch=true에서만 허용합니다. 같은 방의 법령만 허용하며 판례·다른 방·없는 ID는 INVALID_REQUEST입니다. 중복은 제거하고 모든 선택 근거를 검색어 변환 맥락에 전달합니다. 복수 선택은 본문/사건명 검색으로 처리하며 JO 참조법령 필터를 강제하지 않습니다. 검색 실행 시간·API 예산은 기존대로 적용됩니다.", types = {"array", "null"}, example = "[\"00000000-0000-0000-0000-000000000002\",\"00000000-0000-0000-0000-000000000003\"]")

    private java.util.List<@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(
            regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String> caseSearchLawEvidenceIds;

    @jakarta.validation.constraints.AssertTrue(message = "법령 근거 선택에는 판례 검색 동의가 필요합니다.")
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Schema(hidden = true)
    public boolean isCaseSearchSelectionValid() {
        return caseSearchLawEvidenceIds == null || caseSearchLawEvidenceIds.isEmpty() || allowCaseSearch;
    }
}
