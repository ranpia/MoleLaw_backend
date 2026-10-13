package com.MoleLaw_backend.law.controller;

import com.MoleLaw_backend.law.entity.Law;
import com.MoleLaw_backend.law.dto.PrecedentInfo;
import com.MoleLaw_backend.chat.dto.request.FirstMessageRequest;
import com.MoleLaw_backend.law.dto.request.PrecedentSearchRequest;
import com.MoleLaw_backend.law.dto.request.QueryRequest;
import com.MoleLaw_backend.chat.dto.response.GptAnswerResponse;
import com.MoleLaw_backend.law.dto.response.KeywordAndTitleResponse;
import com.MoleLaw_backend.law.dto.response.LawDto;
import com.MoleLaw_backend.law.service.CaseSearchService;
import com.MoleLaw_backend.law.service.ExtractKeyword;
import com.MoleLaw_backend.law.service.FinalAnswer;
import com.MoleLaw_backend.law.service.LawEmbeddingService;
import com.MoleLaw_backend.law.service.LawSearchService;
import com.MoleLaw_backend.auth.security.CustomUserDetails;
import com.MoleLaw_backend.law.util.LawParseUtil;
import com.MoleLaw_backend.law.util.MinistryCodeMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import org.springdoc.core.annotations.ParameterObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Test", description = "기능 구현 전 test API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/test")
public class TestController {

    private final LawSearchService lawSearchService;
    private final ExtractKeyword extractKeyword;
    private final FinalAnswer finalAnswer;
    private final CaseSearchService caseSearchService;
    private final LawEmbeddingService lawEmbeddingService;

    @GetMapping("/lawsearch")
    @Operation(summary = "키워드 기반 법령 목록 조회", description = "keyword로 공공법령 API를 검색합니다. orgCode를 생략하면 부처 제한 없이 조회합니다. JSON 배열로 법령 메타데이터를 반환합니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "성공", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = LawDto.class)), examples = @ExampleObject(value = "[{\"lawName\":\"주택임대차보호법\",\"ministry\":\"국토교통부\",\"lawType\":\"법률\",\"proclamationNo\":\"12345\",\"proclamationDate\":\"20260101\",\"enforcementDate\":\"20260701\"}]"))),
            @ApiResponse(responseCode = "502", description = "공공법령 API 통신에 실패했습니다", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":502,\"code\":\"OPENLAW_API_FAILURE\",\"message\":\"공공법령 API 통신에 실패했습니다\"}}"))),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))})
    public ResponseEntity<List<LawDto>> searchLaws(
            @Parameter(description = "검색할 법령명 또는 키워드; 생략 불가", required = true, example = "주택임대차보호법") @RequestParam String keyword,
            @Parameter(description = "소관 부처 코드; 생략하면 전체 부처 조회", example = "1613000") @RequestParam(required = false) String orgCode
    ) {
        String rawJson = (orgCode == null)
                ? lawSearchService.searchLawByKeyword(keyword)
                : lawSearchService.searchLawByKeyword(keyword, orgCode);

        List<LawDto> lawList = LawParseUtil.parseJsonToLawList(rawJson);
        return ResponseEntity.ok(lawList);
    }

    @PostMapping("/law/keyword")
    @Operation(summary = "질문에서 법령 키워드를 추출하여 검색", description = "content에서 AI로 키워드와 부처를 추출하고 첫 키워드로 검색합니다. 반환 본문은 공공법령 API의 JSON 문자열이며 text/plain으로 전달됩니다. CSRF 헤더가 필요합니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "공공법령 API 원문 문자열", content = @Content(mediaType = "text/plain", schema = @Schema(type = "string"), examples = @ExampleObject(value = "{\"LawSearch\":{\"totalCnt\":\"0\"}}"))),
            @ApiResponse(responseCode = "502", description = "공공법령 API 통신에 실패했습니다", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":502,\"code\":\"OPENLAW_API_FAILURE\",\"message\":\"공공법령 API 통신에 실패했습니다\"}}"))),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))})
    public ResponseEntity<String> getLawByKeyword(@io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "분석할 법률 질문", content = @Content(mediaType = "application/json", schema = @Schema(implementation = FirstMessageRequest.class), examples = @ExampleObject(value = "{\"content\":\"보증금을 돌려받지 못했습니다.\"}"))) @RequestBody FirstMessageRequest request,
                                                  @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails) {

        KeywordAndTitleResponse extracted = extractKeyword.extractKeywords(request.getContent());
        String keyword = extracted.getKeywords().get(0); // 첫 번째 키워드 기준
        String orgCode = MinistryCodeMapper.getCode(extracted.getMinistry());

        String result = (orgCode != null)
                ? lawSearchService.searchLawByKeyword(keyword, orgCode)
                : lawSearchService.searchLawByKeyword(keyword);

        return ResponseEntity.ok(result);
    }


    @PostMapping("/keywords")
    @Operation(summary = "AI 키워드 및 요약 추출", description = "query에서 법률 검색 키워드, 요약 제목, 소관 부처를 추출합니다. CSRF 헤더가 필요합니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "성공", content = @Content(mediaType = "application/json", schema = @Schema(implementation = KeywordAndTitleResponse.class), examples = @ExampleObject(value = "{\"keywords\":[\"임대차\",\"보증금\"],\"summary\":\"보증금 반환 방법\",\"ministry\":\"국토교통부\"}"))),
            @ApiResponse(responseCode = "500", description = "GPT 응답 생성 중 오류 발생 - 키워드 응답 필수 항목 누락", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"GPT_API_FAILURE\",\"message\":\"GPT 응답 생성 중 오류 발생 - 키워드 응답 필수 항목 누락\"}}")))})
    public ResponseEntity<KeywordAndTitleResponse> getKeywordsAndSummary(@io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "분석할 법률 질문", content = @Content(mediaType = "application/json", schema = @Schema(implementation = QueryRequest.class), examples = @ExampleObject(value = "{\"query\":\"보증금을 돌려받지 못했습니다.\"}"))) @RequestBody QueryRequest request) {
        KeywordAndTitleResponse response = extractKeyword.extractKeywords(request.getQuery());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/cases")
    @Operation(summary = "키워드 기반 판례 목록 조회", description = "query 쿼리 파라미터로 공공법령 API 판례를 검색합니다. 결과 키는 외부 API의 한글 필드명을 사용합니다. query에는 별도 필수 또는 길이 검증이 없습니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "성공", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = PrecedentInfo.class)), examples = @ExampleObject(value = "[{\"판례일련번호\":\"100001\",\"사건명\":\"보증금반환\",\"사건번호\":\"2025다12345\",\"선고일자\":\"20260101\",\"법원명\":\"대법원\",\"사건종류명\":\"민사\",\"판결유형\":\"판결\",\"선고\":\"선고\",\"판례상세링크\":\"/precInfoP.do?precSeq=100001\"}]"))),
            @ApiResponse(responseCode = "400", description = "공공법령 API 응답이 올바르지 않습니다", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"OPENLAW_INVALID_RESPONSE\",\"message\":\"공공법령 API 응답이 올바르지 않습니다\"}}"))),
            @ApiResponse(responseCode = "502", description = "공공법령 API 통신에 실패했습니다", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":502,\"code\":\"OPENLAW_API_FAILURE\",\"message\":\"공공법령 API 통신에 실패했습니다\"}}"))),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))})
    public List<PrecedentInfo> getPrecedents(@ParameterObject @ModelAttribute PrecedentSearchRequest request) {
        return caseSearchService.searchCases(request);
    }

    @PostMapping("/answer")
    @Operation(summary = "법률 AI 답변 생성 테스트", description = "query에서 키워드를 추출하고 법령과 판례를 검색하여 답변과 근거 마크다운을 생성합니다. CSRF 헤더가 필요합니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "성공", content = @Content(mediaType = "application/json", schema = @Schema(implementation = GptAnswerResponse.class), examples = @ExampleObject(value = "{\"answer\":\"계약서와 지급 내역을 확인해 주세요.\",\"info\":\"## 관련 법령\\n주택임대차보호법\"}"))),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))})
    public ResponseEntity<GptAnswerResponse> getAnswer(@io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "분석할 법률 질문", content = @Content(mediaType = "application/json", schema = @Schema(implementation = QueryRequest.class), examples = @ExampleObject(value = "{\"query\":\"보증금을 돌려받지 못했습니다.\"}"))) @RequestBody QueryRequest request) {
        KeywordAndTitleResponse response = extractKeyword.extractKeywords(request.getQuery());
        return ResponseEntity.ok(finalAnswer.getAnswer(request.getQuery(), response));
    }

    @PostMapping("/import")
    @Operation(summary = "법령 저장 및 벡터화", description = "name으로 법령을 조회하여 DB에 저장하고 임베딩을 생성합니다. 외부 API 및 벡터 저장소를 호출하는 데이터 변경 작업이며 CSRF 헤더가 필요합니다. 저장된 법령 ID 목록을 문자열로 반환합니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "법령 저장 및 임베딩 생성 완료", content = @Content(mediaType = "text/plain", schema = @Schema(type = "string"), examples = @ExampleObject(value = "✅ 저장 및 벡터화 완료: [1, 2]"))),
            @ApiResponse(responseCode = "400", description = "공공법령 API 응답이 올바르지 않습니다", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"OPENLAW_INVALID_RESPONSE\",\"message\":\"공공법령 API 응답이 올바르지 않습니다\"}}"))),
            @ApiResponse(responseCode = "502", description = "공공법령 API 통신에 실패했습니다", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":502,\"code\":\"OPENLAW_API_FAILURE\",\"message\":\"공공법령 API 통신에 실패했습니다\"}}"))),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))})
    public ResponseEntity<String> importLaw(@Parameter(description = "저장 및 벡터화할 법령명", required = true, example = "주택임대차보호법") @RequestParam String name) {
        List<Law> laws = lawSearchService.saveLawsWithArticles(name);
        lawEmbeddingService.embedLaws(laws);
        return ResponseEntity.ok("✅ 저장 및 벡터화 완료: " +laws.stream().map(Law::getId).toList());
    }
}
