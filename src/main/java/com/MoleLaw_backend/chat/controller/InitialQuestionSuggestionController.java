package com.MoleLaw_backend.chat.controller;

import com.MoleLaw_backend.chat.dto.request.InitialQuestionSuggestionsRequest;
import com.MoleLaw_backend.chat.dto.response.InitialQuestionSuggestionsResponse;
import com.MoleLaw_backend.chat.service.answer.InitialQuestionSuggestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/legal-answers/suggestions")
@Tag(name = "Initial question suggestions", description = "상담 시작 전 초안 기반 추천 질문. 상담방·메시지·근거를 저장하지 않습니다.")
public class InitialQuestionSuggestionController {
    private final InitialQuestionSuggestionService suggestions;

    @PostMapping
    @Operation(summary = "최초 상담 추천 질문", description = "사용자 초안으로 선택 가능한 질문 1~3개를 생성합니다. question은 원문과 추천 방향을 결합하며 기존 상담 API의 content로 전송할 수 있습니다. Unicode 2000자 한도에 여유가 거의 없으면 모델 호출 없이 원문 항목 1개를 반환합니다. 대화 기록·검색·수집·임베딩·DB 저장은 사용하지 않습니다. 선택은 자동으로 검색 동의를 부여하지 않습니다. 인증 쿠키 또는 Bearer와 X-XSRF-TOKEN 헤더가 필요합니다. 프론트에서 명시적 추천 요청 시 호출하고 선택 후에만 상담을 시작하세요.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "추천 질문. 원문은 보존되며 ID는 이번 응답의 표시용 값입니다.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = InitialQuestionSuggestionsResponse.class), examples = @ExampleObject(value = "{\"suggestedQuestions\":[{\"id\":\"suggestion-1\",\"label\":\"공동책임 기준\",\"question\":\"시공 하자와 관리 소홀이 함께 사고를 일으킨 경우 책임이 궁금합니다.\\n\\n위 상황에서 공동책임을 인정하는 일반 기준을 설명해 주세요.\"}]}"))),
            @ApiResponse(responseCode = "400", description = "초안 누락·공백·Unicode 2000자 또는 UTF-8 32KiB 초과·잘못된 JSON", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}}"))),
            @ApiResponse(responseCode = "401", description = "인증 필요; Security 응답", content = @Content(mediaType = "application/json", schema = @Schema(type = "object"), examples = @ExampleObject(value = "{\"error\":\"Unauthorized\"}"))),
            @ApiResponse(responseCode = "403", description = "CSRF·Origin 검사 실패. 필터/컨테이너 응답으로 JSON 본문 형식은 보장하지 않습니다.", content = @Content),
            @ApiResponse(responseCode = "500", description = "모델 호출·출력 계약 검증 실패. 상담은 생성되지 않습니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"GPT_API_FAILURE\",\"message\":\"GPT 응답 생성 중 오류 발생\"}}")))})
    public InitialQuestionSuggestionsResponse suggest(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = InitialQuestionSuggestionsRequest.class), examples = @ExampleObject(value = "{\"query\":\"시공 하자와 관리 소홀이 함께 사고를 일으킨 경우 책임이 궁금합니다.\"}")))
            @Valid @RequestBody InitialQuestionSuggestionsRequest request) {
        return suggestions.suggest(request.query());
    }
}
