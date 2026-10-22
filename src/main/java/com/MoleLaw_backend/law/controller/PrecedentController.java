package com.MoleLaw_backend.law.controller;

import com.MoleLaw_backend.law.dto.response.PrecedentSummaryResponse;
import com.MoleLaw_backend.law.dto.request.PrecedentSearchRequest;
import com.MoleLaw_backend.law.dto.response.PrecedentDetailResponse;
import com.MoleLaw_backend.law.service.search.CaseSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springdoc.core.annotations.ParameterObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

@Tag(name = "Precedent", description = "판례 목록·상세 원문 조회")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/precedents")
public class PrecedentController {
    private final CaseSearchService caseSearchService;
    @GetMapping
    @Operation(summary = "키워드 기반 판례 목록 조회", description = "필수 query(1~200자)를 판례명(search=1) 또는 본문(search=2)에서 검색합니다. referenceLaw는 참조법령(JO), courtType/courtName은 법원 필터입니다. page 기본값은 1, display 기본값은 5입니다. 정상 빈 결과는 []이며 결과 키는 외부 API의 한글 필드명을 사용합니다. Access 쿠키 인증이 필요하며 GET에는 CSRF 헤더가 필요하지 않습니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "성공", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = PrecedentSummaryResponse.class)), examples = @ExampleObject(value = "[{\"판례일련번호\":\"100001\",\"사건명\":\"보증금반환\",\"사건번호\":\"2025다12345\",\"선고일자\":\"20260101\",\"법원명\":\"대법원\",\"사건종류명\":\"민사\",\"판결유형\":\"판결\",\"선고\":\"선고\",\"판례상세링크\":\"/precInfoP.do?precSeq=100001\"}]"))),
            @ApiResponse(responseCode = "400", description = "검색 조건 또는 외부 응답이 올바르지 않습니다", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = {
                    @ExampleObject(name = "invalidRequest", value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}}"),
                    @ExampleObject(name = "invalidResponse", value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"OPENLAW_INVALID_RESPONSE\",\"message\":\"공공법령 API 응답이 올바르지 않습니다\"}}") })),
            @ApiResponse(responseCode = "502", description = "공공법령 API 통신에 실패했습니다", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":502,\"code\":\"OPENLAW_API_FAILURE\",\"message\":\"공공법령 API 통신에 실패했습니다\"}}"))),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))})
    public List<PrecedentSummaryResponse> getPrecedents(@ParameterObject @ModelAttribute PrecedentSearchRequest request,
                                           @Parameter(hidden = true) org.springframework.validation.BindingResult binding) {
        if (binding.hasErrors()) throw new com.MoleLaw_backend.common.exception.MolelawException(
                com.MoleLaw_backend.common.exception.ErrorCode.INVALID_REQUEST);
        return caseSearchService.searchCases(request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "판례 상세 본문 조회", description = "목록에서 얻은 숫자 판례일련번호로 판시사항·판결요지·참조조문·판례내용을 조회합니다. JSON 상세 조회 미지원 자료나 본문 누락은 OPENLAW_INVALID_RESPONSE(400)입니다. 원문의 HTML 표기를 보존하며 DB 저장이나 AI 호출은 수행하지 않습니다. Access 쿠키 인증이 필요하며 GET에는 CSRF 헤더가 필요하지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "판례 상세 조회 성공", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PrecedentDetailResponse.class), examples = @ExampleObject(value = "{\"판례일련번호\":\"100001\",\"사건명\":\"손해배상\",\"사건번호\":\"2025다12345\",\"선고일자\":\"20260101\",\"법원명\":\"대법원\",\"판시사항\":\"손해배상 책임의 성립 요건\",\"판결요지\":\"귀책사유와 손해 발생을 심리하여야 한다.\",\"참조조문\":\"민법 제750조\",\"참조판례\":\"\",\"판례내용\":\"【주문】 원심판결을 파기한다. 【이유】 ...\",\"판례상세링크\":\"https://www.law.go.kr/precInfoP.do?precSeq=100001\"}"))),
            @ApiResponse(responseCode = "400", description = "잘못된 ID 또는 상세 본문 미확보", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = {
                    @ExampleObject(name = "invalidId", value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}}"),
                    @ExampleObject(name = "invalidResponse", value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"OPENLAW_INVALID_RESPONSE\",\"message\":\"공공법령 API 응답이 올바르지 않습니다\"}}") })),
            @ApiResponse(responseCode = "502", description = "공공법령 API 통신 실패", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":502,\"code\":\"OPENLAW_API_FAILURE\",\"message\":\"공공법령 API 통신에 실패했습니다\"}}")))
    })
    public PrecedentDetailResponse getPrecedentDetail(
            @Parameter(description = "목록 응답의 판례일련번호; 숫자 1~20자리", required = true, example = "100001", schema = @Schema(pattern = "[0-9]{1,20}")) @PathVariable String id) {
        return caseSearchService.getCaseDetail(id);
    }

}
