package com.MoleLaw_backend.law.controller;

import com.MoleLaw_backend.law.dto.request.LawQuestionRequest;
import com.MoleLaw_backend.law.dto.response.KeywordExtractionResponse;
import com.MoleLaw_backend.law.dto.response.LawDetailResponse;
import com.MoleLaw_backend.law.service.analysis.KeywordExtractionService;
import com.MoleLaw_backend.chat.service.ChatContentLimits;
import com.MoleLaw_backend.law.service.search.LawSearchService;
import com.MoleLaw_backend.law.util.LawParseUtil;
import com.MoleLaw_backend.law.util.MinistryCodeMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.validation.Valid;

import java.util.List;

@Tag(name = "Law", description = "법령 목록·질문 기반 검색")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/laws")
public class LawController {
    private final LawSearchService lawSearchService;
    private final KeywordExtractionService extractKeyword;
    @GetMapping
    @Operation(summary = "키워드 기반 법령 목록 조회", description = "keyword로 공공법령 API를 검색합니다. orgCode를 생략하면 부처 제한 없이 조회합니다. LawSearch.law의 법령 메타데이터를 JSON 배열로 반환하며 정상 검색 결과가 없으면 []입니다. 잘못된 외부 응답은 400으로 구분합니다. Access 쿠키 인증이 필요하며 GET 요청에는 CSRF 헤더가 필요하지 않습니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "성공", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = LawDetailResponse.class)), examples = @ExampleObject(value = "[{\"lawName\":\"주택임대차보호법\",\"ministry\":\"국토교통부\",\"lawType\":\"법률\",\"proclamationNo\":\"12345\",\"proclamationDate\":\"20260101\",\"enforcementDate\":\"20260701\"}]"))),
            @ApiResponse(responseCode = "400", description = "공공법령 API 응답 구조 또는 JSON이 올바르지 않습니다", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"OPENLAW_INVALID_RESPONSE\",\"message\":\"공공법령 API 응답이 올바르지 않습니다\"}}"))),
            @ApiResponse(responseCode = "502", description = "공공법령 API 통신에 실패했습니다", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":502,\"code\":\"OPENLAW_API_FAILURE\",\"message\":\"공공법령 API 통신에 실패했습니다\"}}"))),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))})
    public ResponseEntity<List<LawDetailResponse>> searchLaws(
            @Parameter(description = "검색할 법령명 또는 키워드; 생략 불가", required = true, example = "주택임대차보호법") @RequestParam String keyword,
            @Parameter(description = "소관 부처 코드; 생략하면 전체 부처 조회", example = "1613000") @RequestParam(required = false) String orgCode
    ) {
        String rawJson = (orgCode == null)
                ? lawSearchService.searchLawByKeyword(keyword)
                : lawSearchService.searchLawByKeyword(keyword, orgCode);

        List<LawDetailResponse> lawList = LawParseUtil.parseJsonToLawList(rawJson);
        return ResponseEntity.ok(lawList);
    }

    @PostMapping("/search")
    @Operation(summary = "질문에서 법령 키워드를 추출하여 검색", description = "query(공백 불가·Unicode 최대 2000자·UTF-8 최대 32KiB)에서 AI로 키워드와 부처를 추출하고 첫 키워드로 검색합니다. 반환 본문은 공공법령 API의 JSON 문자열이며 text/plain으로 전달됩니다. CSRF 헤더가 필요합니다.")
    @ApiResponses({@ApiResponse(responseCode = "400", description = "공백 질문 또는 Unicode 2000자 한도 초과 등 유효하지 않은 요청", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}}"))),
            @ApiResponse(responseCode = "200", description = "공공법령 API 원문 문자열", content = @Content(mediaType = "text/plain", schema = @Schema(type = "string"), examples = @ExampleObject(value = "{\"LawSearch\":{\"totalCnt\":\"0\"}}"))),
            @ApiResponse(responseCode = "502", description = "공공법령 API 통신에 실패했습니다", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":502,\"code\":\"OPENLAW_API_FAILURE\",\"message\":\"공공법령 API 통신에 실패했습니다\"}}"))),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))})
    public ResponseEntity<String> getLawByKeyword(@io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "분석할 법률 질문", content = @Content(mediaType = "application/json", schema = @Schema(implementation = LawQuestionRequest.class), examples = @ExampleObject(value = "{\"query\":\"보증금을 돌려받지 못했습니다.\"}"))) @Valid @RequestBody LawQuestionRequest request) {

        ChatContentLimits.question(request.getQuery());
        KeywordExtractionResponse extracted = extractKeyword.extractKeywords(request.getQuery());
        String keyword = extracted.getKeywords().get(0); // 첫 번째 키워드 기준
        String orgCode = MinistryCodeMapper.getCode(extracted.getMinistry());

        String result = (orgCode != null)
                ? lawSearchService.searchLawByKeyword(keyword, orgCode)
                : lawSearchService.searchLawByKeyword(keyword);

        return ResponseEntity.ok(result);
    }

}
