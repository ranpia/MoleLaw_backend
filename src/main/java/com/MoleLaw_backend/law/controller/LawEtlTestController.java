package com.MoleLaw_backend.law.controller;

import com.MoleLaw_backend.law.dto.request.LawEtlTestRequest;
import com.MoleLaw_backend.law.dto.request.LawReindexTestRequest;
import com.MoleLaw_backend.law.dto.vector.LawIndexResult;
import com.MoleLaw_backend.law.dto.request.LawQuestionRequest;
import com.MoleLaw_backend.law.dto.response.LawEtlTestResponse;
import com.MoleLaw_backend.law.dto.response.LawRagTestResponse;
import com.MoleLaw_backend.law.service.etl.LawEtlTestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Tag(name = "법령 ETL 테스트", description = "local 프로필과 APP_LAW_VECTOR_ENABLED=true에서만 노출됩니다. 법령 수집·재색인·상위 15개 검색을 검증합니다. 실제 MySQL·임베딩·Qdrant를 사용합니다.")
@RestController
@Profile("local & !test")
@ConditionalOnProperty(prefix = "app.law-vector", name = "enabled", havingValue = "true")
@RequestMapping("/api/local/law-etl")
@RequiredArgsConstructor
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "입력 조건 오류", content = @Content(mediaType = "application/json",
        schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"),
        examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}}"))),
    @ApiResponse(responseCode = "401", description = "인증 없음. 보안 필터가 거절하며 JSON 본문을 보장하지 않습니다.", content = @Content),
    @ApiResponse(responseCode = "403", description = "권한 부족 또는 CSRF 누락/불일치. JSON 본문을 보장하지 않습니다.", content = @Content),
    @ApiResponse(responseCode = "503", description = "외부 장애·실행 한도 또는 임베딩 대기열 초과. 부분 완료 색인은 유지됩니다.",
        content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"),
        examples = {
            @ExampleObject(name = "unavailable", value = "{\"success\":false,\"error\":{\"status\":503,\"code\":\"RETRIEVAL_UNAVAILABLE\",\"message\":\"법령 근거 검색을 사용할 수 없습니다. 잠시 후 다시 시도하세요.\"}}"),
            @ExampleObject(name = "busy", value = "{\"success\":false,\"error\":{\"status\":503,\"code\":\"RETRIEVAL_BUSY\",\"message\":\"임베딩 처리량이 초과되었습니다. 잠시 후 다시 시도하세요.\"}}")
        }))
})
public class LawEtlTestController {
    private final LawEtlTestService service;

    @PostMapping("/reindex")
    @Operation(summary = "저장 원문 재색인", description = "법령 API를 재호출하지 않고 MySQL 원문으로 ETL을 실행합니다. 같은 원문·설정의 완료 색인도 포인트 수·ID·출처 메타데이터·벡터를 재조회해 검증한 후 재사용합니다. 저장 누락이 확인되면 새 세대로 한 번 재생성하며 실패했던 원문은 처음부터 재처리합니다. 통신 장애를 누락으로 취급하지 않습니다. 알 수 없는 UUID는 400입니다. 인증 쿠키 또는 Bearer와 CSRF가 필요합니다. /api/auth/csrf의 토큰을 X-XSRF-TOKEN 헤더로 전달하세요.")
    @ApiResponse(responseCode = "200", description = "재색인 완료 또는 완료 세대 재사용", content = @Content(mediaType = "application/json",
        schema = @Schema(implementation = LawIndexResult.class),
        examples = @ExampleObject(value = "{\"referenceId\":\"11111111-1111-1111-1111-111111111111\",\"collection\":\"molelaw_service_kure\",\"articleCount\":1,\"chunkCount\":2}")))
    public LawIndexResult reindex(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = LawReindexTestRequest.class),
            examples = @ExampleObject(value = "{\"referenceId\":\"11111111-1111-1111-1111-111111111111\"}")))
        @Valid @RequestBody LawReindexTestRequest request) {
        return service.reindex(request.referenceId());
    }

    @PostMapping("/index")
    @Operation(summary = "법령 검색·수집·ETL 실행", description = "법령 Tool→MySQL Reader→조문 Transformer→로컬 임베딩→Qdrant writer를 동기 실행합니다. 페이지당 후보 1개, 최대 3페이지입니다. filters 생략 시 완전 일치합니다. nextPage로 이어서 요청할 수 있습니다. 동일 완료 원문/설정은 저장 포인트 검증 후 재사용하며 새 색인도 검증 통과 후 공개합니다. 실패 시 동일 조건으로 수동 재시도합니다. 인증 쿠키 또는 Bearer와 CSRF 헤더가 필요합니다. /api/auth/csrf에서 토큰을 받아 X-XSRF-TOKEN 헤더로 전송하세요.")
    @ApiResponse(responseCode = "200", description = "색인 완료 또는 정상 빈 결과", content = @Content(mediaType = "application/json",
        schema = @Schema(implementation = LawEtlTestResponse.class),
        examples = @ExampleObject(value = "{\"executionId\":\"etl-1\",\"pagesVisited\":1,\"nextPage\":null,\"indexes\":[{\"referenceId\":\"source-1\",\"collection\":\"molelaw_service_kure\",\"articleCount\":1,\"chunkCount\":2}]}")))
    public LawEtlTestResponse index(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = LawEtlTestRequest.class),
            examples = {
                @ExampleObject(name = "정확한 법령명", summary = "민법 완전 일치 검색", value = "{\"query\":\"민법\",\"startPage\":1,\"maxPages\":3}"),
                @ExampleObject(name = "검색 조건 지정", summary = "ID·완전 일치·시행일 정렬 조건", value = "{\"query\":\"민법\",\"startPage\":1,\"maxPages\":3,\"filters\":{\"lawId\":\"001706\",\"exactName\":true,\"sort\":\"efdes\"}}")
            }))
        @Valid @RequestBody LawEtlTestRequest request) {
        return service.collect(request);
    }

    @PostMapping("/search")
    @Operation(summary = "완료 색인에서 상위 15개 조문 검색", description = "질문을 임베딩하고 공개 완료 세대에서 서로 다른 조문 최대 15개를 반환합니다. 법령 수집과 답변 생성은 하지 않습니다. 인증 쿠키 또는 Bearer와 CSRF가 필요합니다. /api/auth/csrf의 토큰을 X-XSRF-TOKEN 헤더로 전달하세요.")
    @ApiResponse(responseCode = "200", description = "검색 결과. 완료 색인이 없으면 빈 배열",
        content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = LawRagTestResponse.class)),
            examples = {
                @ExampleObject(name = "검색 성공", value = "[{\"unitId\":\"123:2\",\"referenceId\":\"11111111-1111-1111-1111-111111111111\",\"externalId\":\"123\",\"lawId\":\"001706\",\"effectiveDate\":\"20260101\",\"title\":\"민법 제2조(신의성실)\",\"text\":\"권리의 행사와 의무의 이행은 신의에 좇아 성실히 하여야 한다.\",\"articleText\":\"권리의 행사와 의무의 이행은 신의에 좇아 성실히 하여야 한다. 권리는 남용하지 못한다.\",\"sourceUrl\":\"https://www.law.go.kr/lsInfoP.do?lsiSeq=123\",\"score\":0.82,\"modelRevision\":\"example-revision\",\"configFingerprint\":\"example-fingerprint\",\"generation\":\"22222222-2222-2222-2222-222222222222\"}]"),
                @ExampleObject(name = "검색 결과 없음", value = "[]")
            }))
    public List<LawRagTestResponse> search(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = LawQuestionRequest.class),
            examples = @ExampleObject(value = "{\"query\":\"신의성실 원칙\"}")))
        @Valid @RequestBody LawQuestionRequest request) {
        return service.search(request.getQuery()).stream().map(LawRagTestResponse::from).toList();
    }
}
