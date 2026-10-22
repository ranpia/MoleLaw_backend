package com.MoleLaw_backend.law.controller;

import com.MoleLaw_backend.law.dto.request.LawQuestionRequest;
import com.MoleLaw_backend.law.dto.response.KeywordExtractionResponse;
import com.MoleLaw_backend.law.service.analysis.KeywordExtractionService;
import com.MoleLaw_backend.chat.service.ChatContentLimits;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;

@Tag(name = "Law keywords", description = "법률 질문의 키워드·요약·부처 분석")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/law-keywords")
public class KeywordController {
    private final KeywordExtractionService extractKeyword;
    @PostMapping
    @Operation(summary = "AI 키워드 및 요약 추출", description = "query(공백 불가·Unicode 최대 2000자·UTF-8 최대 32KiB)에서 법률 검색 키워드, 요약 제목, 소관 부처를 추출합니다. CSRF 헤더가 필요합니다.")
    @ApiResponses({@ApiResponse(responseCode = "400", description = "공백 질문 또는 Unicode 2000자 한도 초과 등 유효하지 않은 요청", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}}"))),
            @ApiResponse(responseCode = "200", description = "성공", content = @Content(mediaType = "application/json", schema = @Schema(implementation = KeywordExtractionResponse.class), examples = @ExampleObject(value = "{\"keywords\":[\"임대차\",\"보증금\"],\"summary\":\"보증금 반환 방법\",\"ministry\":\"국토교통부\"}"))),
            @ApiResponse(responseCode = "500", description = "GPT 응답 생성 중 오류 발생 - 키워드 응답 필수 항목 누락", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"GPT_API_FAILURE\",\"message\":\"GPT 응답 생성 중 오류 발생 - 키워드 응답 필수 항목 누락\"}}")))})
    public ResponseEntity<KeywordExtractionResponse> getKeywordsAndSummary(@io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "분석할 법률 질문", content = @Content(mediaType = "application/json", schema = @Schema(implementation = LawQuestionRequest.class), examples = @ExampleObject(value = "{\"query\":\"보증금을 돌려받지 못했습니다.\"}"))) @Valid @RequestBody LawQuestionRequest request) {
        ChatContentLimits.question(request.getQuery());
        KeywordExtractionResponse response = extractKeyword.extractKeywords(request.getQuery());
        return ResponseEntity.ok(response);
    }

}
