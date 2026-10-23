package com.MoleLaw_backend.law.controller;

import com.MoleLaw_backend.law.dto.request.AiLawSearchProbeRequest;
import com.MoleLaw_backend.law.dto.request.LawQuestionRequest;
import com.MoleLaw_backend.law.dto.response.LawRetrievalComparisonResponse;
import com.MoleLaw_backend.law.service.retrieval.LawRetrievalComparisonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local & !test")
@RequestMapping("/api/local/law-retrieval")
@RequiredArgsConstructor
@Tag(name = "법령 검색 비교", description = "local 전용 조문 API·Qdrant 비교. 실제 검색만 수행하며 LLM 답변·수집·색인은 실행하지 않습니다.")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "잘못된 검색어·페이지·개수", content = @Content(mediaType = "application/json",
        schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"),
        examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}}"))),
    @ApiResponse(responseCode = "401", description = "인증 필요. 보안 필터 응답이며 JSON 본문을 보장하지 않습니다.", content = @Content),
    @ApiResponse(responseCode = "403", description = "CSRF 누락·권한 오류. JSON 본문을 보장하지 않습니다.", content = @Content)
})
public class LawRetrievalComparisonController {
    private final LawRetrievalComparisonService service;

    @PostMapping("/ai-search")
    @Operation(summary = "공식 조문 검색 Tool 단독 실행", description = "aiSearch의 search=0 법령 조문을 조회합니다. search=1~3 별표·행정규칙은 지원하지 않습니다. 인증 쿠키 또는 Bearer와 CSRF가 필요하며 /api/auth/csrf에서 받은 토큰을 X-XSRF-TOKEN에 전달하세요. 제공자 장애도 HTTP 200의 status=UNAVAILABLE/errorCode로 반환하며 빈 결과와 구분합니다. 자동 대체 검색·답변·저장은 없습니다.")
    @ApiResponse(responseCode = "200", description = "검색 결과 또는 진단 가능한 제공자 장애", content = @Content(mediaType = "application/json",
        schema = @Schema(implementation = LawRetrievalComparisonResponse.BackendResult.class),
        examples = {
            @ExampleObject(name = "빈 결과", value = "{\"backend\":\"AI_SEARCH\",\"status\":\"EMPTY\",\"elapsedMillis\":120,\"returnedCount\":0,\"providerTotalCount\":0,\"hasNext\":false,\"hits\":[]}"),
            @ExampleObject(name = "제공자 장애", value = "{\"backend\":\"AI_SEARCH\",\"status\":\"UNAVAILABLE\",\"elapsedMillis\":120,\"returnedCount\":0,\"errorCode\":\"API_FAILURE\",\"hits\":[]}")
        }))
    public LawRetrievalComparisonResponse.BackendResult search(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = AiLawSearchProbeRequest.class),
                examples = @ExampleObject(value = "{\"query\":\"공항 높이 제한\",\"page\":1,\"display\":15}")))
            @Valid @RequestBody AiLawSearchProbeRequest request) {
        return service.searchAi(request.toToolRequest());
    }

    @PostMapping("/compare")
    @Operation(summary = "동일 검색어로 조문 API와 Qdrant 비교", description = "같은 검색어를 변환 없이 양쪽에 전달해 최대 15개 결과·경과 시간·공통 법령 ID를 반환합니다. API는 첫 페이지, Qdrant는 현재 공개 완료 색인만 검색하므로 모집단이 다릅니다. 호출은 순차 실행하며 각 경로 시간을 별도 측정합니다. API 장애·Qdrant 미설정도 해당 경로 status로 반환하고 상대 경로 결과를 유지합니다. 공통 법령 수는 정확도 점수가 아닙니다. 인증 쿠키 또는 Bearer와 CSRF가 필요합니다. /api/auth/csrf의 토큰을 X-XSRF-TOKEN으로 전달하세요. 수집·LLM·상담방 저장은 수행하지 않습니다.")
    @ApiResponse(responseCode = "200", description = "경로별 성공·빈 결과·장애를 포함한 비교 결과", content = @Content(mediaType = "application/json",
        schema = @Schema(implementation = LawRetrievalComparisonResponse.class),
        examples = @ExampleObject(value = "{\"query\":\"공항 높이 제한\",\"aiSearch\":{\"backend\":\"AI_SEARCH\",\"status\":\"UNAVAILABLE\",\"elapsedMillis\":100,\"returnedCount\":0,\"errorCode\":\"API_FAILURE\",\"hits\":[]},\"qdrant\":{\"backend\":\"QDRANT\",\"status\":\"EMPTY\",\"elapsedMillis\":10,\"returnedCount\":0,\"hits\":[]},\"commonLawIds\":[]}")))
    public LawRetrievalComparisonResponse compare(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = LawQuestionRequest.class),
                examples = @ExampleObject(value = "{\"query\":\"공항 높이 제한\"}")))
            @Valid @RequestBody LawQuestionRequest request) {
        return service.compare(request.getQuery());
    }
}
