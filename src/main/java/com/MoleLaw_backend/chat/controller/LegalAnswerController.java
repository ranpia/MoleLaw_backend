package com.MoleLaw_backend.chat.controller;

import com.MoleLaw_backend.chat.dto.request.LegalAnswerRequest;

import com.MoleLaw_backend.chat.dto.response.LegalAnswerResponse;
import com.MoleLaw_backend.chat.service.answer.LegalAnswerService;
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

@Tag(name = "Legal answer", description = "상담방 저장 없이 법령 근거로 답변 생성")
@RestController
@lombok.extern.slf4j.Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/legal-answers")
public class LegalAnswerController {
    private final LegalAnswerService initialAnswers;
    @PostMapping
    @Operation(summary = "법률 AI 답변 생성", description = "공백이 아닌 query(Unicode 최대 2000자·UTF-8 최대 32KiB)로 Qdrant 상위 15개 조문을 검색합니다. 근거 수집 LLM 판단에 따라 예산 안에서 추가 법령 수집·벡터화·재검색을 수행하고 충분한 근거가 있을 때 최초 답변 Client를 호출해 인용 ID를 검증합니다. 서버가 생성한 출처 마크다운을 함께 반환하며 상담방·메시지는 저장하지 않습니다. 근거 부족은 422, 검색 의존 서비스 비활성화/실패·임베딩 포화는 503입니다. Access 쿠키 인증과 CSRF 헤더가 필요합니다.")
    @ApiResponses({@ApiResponse(responseCode = "422", description = "확보한 법령 근거가 충분하지 않습니다. 답변을 생성하지 않습니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":422,\"code\":\"EVIDENCE_INSUFFICIENT\",\"message\":\"확보한 근거가 충분하지 않습니다. 질문을 구체화해 주세요.\"}}"))),
            @ApiResponse(responseCode = "503", description = "검색 서비스 비활성화/실패 또는 임베딩 대기열 포화", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = {
                @ExampleObject(name = "검색 서비스 사용 불가", value = "{\"success\":false,\"error\":{\"status\":503,\"code\":\"RETRIEVAL_UNAVAILABLE\",\"message\":\"법령 근거 검색을 사용할 수 없습니다. 잠시 후 다시 시도하세요.\"}}"),
                @ExampleObject(name = "임베딩 대기열 포화", value = "{\"success\":false,\"error\":{\"status\":503,\"code\":\"RETRIEVAL_BUSY\",\"message\":\"임베딩 처리량이 초과되었습니다. 잠시 후 다시 시도하세요.\"}}") })),
            @ApiResponse(responseCode = "400", description = "공백 질문 또는 Unicode 2000자 한도 초과 등 유효하지 않은 요청", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}}"))),
            @ApiResponse(responseCode = "200", description = "성공", content = @Content(mediaType = "application/json", schema = @Schema(implementation = LegalAnswerResponse.class), examples = @ExampleObject(value = "{\"answer\":\"계약서와 지급 내역을 확인해 주세요.\",\"info\":\"## 관련 법령\\n주택임대차보호법\"}"))),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))})
    public ResponseEntity<LegalAnswerResponse> getAnswer(@io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "분석할 법률 질문", content = @Content(mediaType = "application/json", schema = @Schema(implementation = LegalAnswerRequest.class), examples = @ExampleObject(value = "{\"query\":\"보증금을 돌려받지 못했습니다.\"}"))) @Valid @RequestBody LegalAnswerRequest request) {
        ChatContentLimits.question(request.getQuery());
        log.info("Initial answer request path=/api/legal-answers format=JSON state=STARTED");
        var answer = initialAnswers.getAnswer(request.getQuery());
        log.info("Initial answer request path=/api/legal-answers format=JSON state=COMPLETED");
        return ResponseEntity.ok(answer);
    }

}
