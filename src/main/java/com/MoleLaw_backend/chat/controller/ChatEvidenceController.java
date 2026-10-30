package com.MoleLaw_backend.chat.controller;

import com.MoleLaw_backend.auth.security.CustomUserDetails;
import com.MoleLaw_backend.chat.dto.response.EvidenceDetailResponse;
import com.MoleLaw_backend.chat.dto.response.EvidenceSummaryResponse;
import com.MoleLaw_backend.chat.service.evidence.ChatEvidenceQueryService;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat-rooms/{roomId}/evidence")
@Tag(name = "Chat Evidence", description = "상담방 소유자만 조회·삭제할 수 있는 법령·판례 근거. 인증 쿠키 또는 Bearer가 필요합니다. GET은 CSRF·요청 본문 없이 조회하고 DELETE는 /api/auth/csrf에서 받은 X-XSRF-TOKEN이 필요하며 본문은 없습니다.")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":401,\"code\":\"UNAUTHORIZED\",\"message\":\"인증이 필요합니다.\"}}"))),
        @ApiResponse(responseCode = "403", description = "다른 사용자 방 접근 차단", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":403,\"code\":\"UNAUTHORIZED_CHATROOM_ACCESS\",\"message\":\"본인의 채팅방이 아닙니다.\"}}"))),
        @ApiResponse(responseCode = "404", description = "방 또는 방 소속 근거가 없음", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = {
                @ExampleObject(name = "방 없음", value = "{\"success\":false,\"error\":{\"status\":404,\"code\":\"CHATROOM_NOT_FOUND\",\"message\":\"채팅방을 찾을 수 없습니다.\"}}"),
                @ExampleObject(name = "근거 없음", value = "{\"success\":false,\"error\":{\"status\":404,\"code\":\"NOT_FOUND\",\"message\":\"대상을 찾을 수 없습니다.\"}}") })),
        @ApiResponse(responseCode = "500", description = "원문·스냅샷 검증 실패", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"GPT_API_FAILURE\",\"message\":\"GPT 응답 생성 중 오류 발생\"}}"))) })
public class ChatEvidenceController {
    private final ChatEvidenceQueryService evidence;
    private final com.MoleLaw_backend.chat.service.evidence.ChatEvidenceService evidenceStore;

    @org.springframework.web.bind.annotation.DeleteMapping("/{evidenceId}")
    @Operation(summary = "상담방의 저장된 근거 삭제", description = "본인 방의 저장된 법령·판례 근거를 목록과 이후 상담 맥락에서 제거합니다. Access 쿠키 또는 Bearer 인증과 X-XSRF-TOKEN 헤더가 필요합니다. /api/auth/csrf의 JSON 토큰을 헤더에 전달하세요. 요청 본문은 없으며 성공은 본문 없는 204입니다. 최초 원문 스냅샷과 다른 방·공유 원문은 보존합니다. 기존 메시지는 다시 쓰지 않으며 삭제된 인용 ID의 본문 조회는 404입니다. 이미 실행 중인 요청은 시작 시 읽은 근거를 사용할 수 있으므로 생성이 끝난 뒤 삭제하세요. PREVIEW 근거는 저장 전이므로 화면에서만 제외하며 이 API로 삭제할 수 없습니다. 중복 삭제·다른 방 소속·없는 근거는 404입니다. 삭제 후 GET /api/chat-rooms/{roomId}/evidence로 목록을 다시 조회하세요.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "방의 근거 삭제 완료. 응답 본문 없음", content = @Content),
            @ApiResponse(responseCode = "400", description = "UUID 형식이 아닌 근거 ID", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}}"))) })
    public org.springframework.http.ResponseEntity<Void> remove(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
            @Parameter(description = "본인 소유 방 ID", example = "42", required = true) @PathVariable Long roomId,
            @Parameter(description = "삭제할 저장된 근거 UUID", example = "00000000-0000-0000-0000-000000000001", required = true) @PathVariable String evidenceId) {
        evidenceStore.remove(user.getUser().getId(), roomId, evidenceId);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @GetMapping
    @Operation(summary = "상담방 근거 목록", description = "최초 법령 스냅샷과 성공한 후속 답변에서 인용한 판례 중 사용자가 삭제하지 않은 근거를 반환합니다. 본문은 포함하지 않으며 인용 UUID는 방 안에서 유지됩니다. 검색·LLM 호출은 없습니다.")
    @ApiResponse(responseCode = "200", description = "근거 메타데이터 목록", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = EvidenceSummaryResponse.class)), examples = @ExampleObject(value = "[{\"id\":\"00000000-0000-0000-0000-000000000001\",\"kind\":\"CASE_BODY\",\"sourceId\":\"12345\",\"title\":\"손해배상(기)\",\"sourceUrl\":\"https://www.law.go.kr/precInfoP.do?precSeq=12345\",\"lawId\":null,\"effectiveDate\":null}]")))
    public List<EvidenceSummaryResponse> list(@Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
            @Parameter(description = "본인 소유 방 ID", example = "42", required = true) @PathVariable Long roomId) {
        return evidence.list(user.getUser().getId(), roomId);
    }

    @GetMapping("/{evidenceId}")
    @Operation(summary = "상담방 근거 본문", description = "근거 UUID로 해당 방의 보존된 원문을 표시용 일반 텍스트로 조회합니다. 본문·판례 요지의 HTML 태그를 제거하고 엔티티와 줄바꿈을 변환합니다. 원문 저장값·출처 ID는 유지합니다. 다른 방의 근거 ID는 사용할 수 없으며 수집이나 원문 교체는 수행하지 않습니다.")
    @ApiResponse(responseCode = "200", description = "근거 메타데이터와 본문", content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceDetailResponse.class), examples = @ExampleObject(value = "{\"evidence\":{\"id\":\"00000000-0000-0000-0000-000000000001\",\"kind\":\"CASE_BODY\",\"sourceId\":\"12345\",\"title\":\"손해배상(기)\",\"sourceUrl\":\"https://www.law.go.kr/precInfoP.do?precSeq=12345\",\"lawId\":null,\"effectiveDate\":null},\"content\":\"판결 원문 본문\"}")))
    public EvidenceDetailResponse detail(@Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
            @Parameter(description = "본인 소유 방 ID", example = "42", required = true) @PathVariable Long roomId,
            @Parameter(description = "해당 방의 근거 UUID", example = "00000000-0000-0000-0000-000000000001", required = true) @PathVariable String evidenceId) {
        return evidence.detail(user.getUser().getId(), roomId, evidenceId);
    }
}
