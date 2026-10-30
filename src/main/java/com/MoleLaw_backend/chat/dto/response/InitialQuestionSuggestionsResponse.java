package com.MoleLaw_backend.chat.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "최초 상담용 추천 질문. 생성만으로 상담·검색·저장을 실행하지 않습니다.")
public record InitialQuestionSuggestionsResponse(
        @Schema(description = "선택 가능한 추천 질문 1~3개. 긴 초안은 원문 그대로 질문하는 항목 1개를 반환합니다.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<SuggestedQuestion> suggestedQuestions) {
    public InitialQuestionSuggestionsResponse { suggestedQuestions = List.copyOf(suggestedQuestions); }

    @Schema(name = "SuggestedInitialQuestion", description = "프론트에서 선택하면 question을 기존 상담 API의 content로 전송합니다.")
    public record SuggestedQuestion(
            @Schema(description = "이번 응답 안에서만 사용하는 표시용 ID. 실행 권한이나 검색 동의가 아닙니다.", example = "suggestion-1", requiredMode = Schema.RequiredMode.REQUIRED) String id,
            @Schema(description = "버튼에 표시할 짧은 문구", example = "공동책임 기준", requiredMode = Schema.RequiredMode.REQUIRED) String label,
            @Schema(description = "사용자 원문과 추천 질문 방향을 결합한 실제 전송 문장. 원문 사실은 생략하지 않습니다.", example = "시공 하자와 관리 소홀이 함께 사고를 일으킨 경우 책임이 궁금합니다.\n\n위 상황에서 공동책임을 인정하는 일반 기준을 설명해 주세요.", requiredMode = Schema.RequiredMode.REQUIRED) String question) { }
}
