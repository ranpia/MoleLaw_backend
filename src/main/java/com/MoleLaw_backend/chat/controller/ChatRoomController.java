package com.MoleLaw_backend.chat.controller;

import com.MoleLaw_backend.chat.dto.request.CreateChatRoomRequest;
import com.MoleLaw_backend.chat.dto.request.CreateMessageRequest;
import com.MoleLaw_backend.chat.dto.response.ChatRoomResponse;
import com.MoleLaw_backend.chat.dto.response.ChatRoomCreationResponse;
import com.MoleLaw_backend.chat.dto.response.MessageResponse;
import com.MoleLaw_backend.chat.service.ChatService;
import com.MoleLaw_backend.auth.security.CustomUserDetails;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import com.MoleLaw_backend.chat.dto.request.ChangeChatRoomTitleRequest;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import jakarta.validation.Valid;

import java.util.List;

@Tag(name = "Chat", description = "채팅 관련 API")
@RestController
@lombok.extern.slf4j.Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/chat-rooms")
public class ChatRoomController {

    private final ChatService chatService;

    @GetMapping
    @Operation(summary = "내 채팅방 목록 조회", description = "본인 소유 방을 createdAt 내림차순, 같은 시각은 ID 내림차순으로 페이지 조회합니다. 응답은 배열이며 page 기본 0, size 기본 20(최대 100)입니다. preview는 시간·ID 오름차순 최초 메시지입니다.")
    @ApiResponses({@ApiResponse(responseCode = "400", description = "질문 또는 페이지 파라미터가 유효하지 않습니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}}"))),
            @ApiResponse(responseCode = "200", description = "성공", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ChatRoomResponse.class)), examples = @ExampleObject(value = "[{\"id\":42,\"title\":\"보증금 상담\",\"preview\":\"보증금을 돌려받지 못했습니다.\",\"createdAt\":\"2026-10-13T10:00:00\"}]"))),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))})
    public List<ChatRoomResponse> getMyRooms(@Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "0부터 시작하는 페이지", example = "0", schema = @Schema(minimum = "0", defaultValue = "0")) @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지당 방 개수(1–100)", example = "20", schema = @Schema(minimum = "1", maximum = "100", defaultValue = "20")) @RequestParam(defaultValue = "20") int size) {
        return chatService.getMyChatRooms(userDetails.getUser(), page, size);
    }

    @GetMapping("/{roomId}/messages")
    @Operation(summary = "채팅방 메시지 전체 조회", description = "본인 소유 메시지를 UTC timestamp·ID 오름차순으로 반환합니다. sender는 USER, BOT, INFO입니다. INFO는 DB 메시지가 아니라 고정 근거 스냅샷을 서버에서 렌더링하여 최초 BOT 다음에 삽입하며 timestamp는 해당 BOT과 같습니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "성공", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = MessageResponse.class)), examples = @ExampleObject(value = "[{\"sender\":\"BOT\",\"content\":\"계약서와 지급 내역을 확인해 주세요.\",\"timestamp\":\"2026-10-13T10:00:00\"}]"))),
            @ApiResponse(responseCode = "403", description = "본인의 채팅방이 아닙니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":403,\"code\":\"UNAUTHORIZED_CHATROOM_ACCESS\",\"message\":\"본인의 채팅방이 아닙니다.\"}}"))),
            @ApiResponse(responseCode = "404", description = "채팅방을 찾을 수 없습니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":404,\"code\":\"CHATROOM_NOT_FOUND\",\"message\":\"채팅방을 찾을 수 없습니다.\"}}"))),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))})
    public List<MessageResponse> getMessages(@Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
                                             @Parameter(description = "본인 소유 채팅방 ID", required = true, example = "42") @PathVariable Long roomId) {
        return chatService.getMessages(userDetails.getUser(), roomId);
    }

    @PostMapping("/{roomId}/messages")
    @Operation(summary = "기존 채팅방에 질문 추가", description = "본인 소유 채팅방의 대화를 Advisor로 복원하고 최초 근거 스냅샷과 함께 전달합니다. 답변의 [[UUID]] 인용을 해당 방 근거 ID와 검증한 후 질문·답변을 함께 저장합니다. 스냅샷 누락·잘못된 인용은 GPT_API_FAILURE로 실패하며 기록을 저장하지 않습니다. 후속 법령 수집·임베딩은 실행하지 않습니다. 판례 보강이 필요하면 먼저 동의를 묻고 caseSearchRequested=true를 반환합니다. 사용자가 승인한 다음 요청의 allowCaseSearch=true일 때만 외부 판례 검색·본문 수집 도구를 제공합니다. 동의는 이번 요청에만 적용되며 기본값은 false입니다. 일반 후속 질문은 판례 본문 읽기·검색을 실행하지 않습니다. 최신 요청이 기존 판례 자체의 판단 이유·내용·비교를 명시한 경우에만 기존 판례를 로컬로 읽으며 새 검색과 동의 요청은 하지 않습니다. caseSearchLawEvidenceIds는 사용자가 선택한 법령 근거 UUID 배열이며 개수 상한이 없습니다. GET /api/chat-rooms/{roomId}/evidence의 LAW_ 항목 id를 사용합니다. 생략/null/[]이면 법령을 자동 선택하지 않고 원래 검색 요청만으로 검색합니다. 비어 있지 않은 목록은 allowCaseSearch=true에서만 지정합니다. 모든 선택 근거를 검색어 변환 맥락으로 전달하며 중복은 제거합니다. 같은 방의 법령만 허용하고 판례·다른 방·없는 ID는 400 INVALID_REQUEST입니다. 복수 선택은 본문/사건명 검색으로 처리하며 참조법령 필터를 강제하지 않습니다. 선택 개수와 별개로 기존 실행 시간·API 예산은 적용됩니다. 동의 문장은 검색 주제에서 제외하고 직전 동의 요청의 원래 사용자 질문을 복원합니다. Access 쿠키 또는 Bearer 인증과 X-XSRF-TOKEN 헤더가 필요합니다. 승인 후에는 기본 본문 검색을 사용하고 LLM이 필요에 따라 사건명 또는 참조법령 필터 범위를 선택합니다. 법률용어 변환 모델의 질의별로 선택 범위의 전체 페이지를 예산 안에서 수집하고 중복 후보를 제거하며 수집 미완료를 별도 표시합니다. 기본 회차당 최대 4개 판결 본문 발췌를 관련성 리랭크해 상위 2개를 읽기 후보로 제공합니다. 목록만으로 인용할 수 없으며 실제 읽고 인용한 새 판례를 질문·답변과 함께 원자적으로 저장합니다. 판례 API 장애는 빈 결과와 구분해 모델에 전달합니다. 근거 목록·본문은 /api/chat-rooms/{roomId}/evidence 및 /{evidenceId}에서 별도로 조회합니다. JSON content(Unicode 최대 2000자·UTF-8 최대 32KiB)와 CSRF 토큰 헤더를 전달합니다.")
    @ApiResponses({@ApiResponse(responseCode = "400", description = "공백 질문·2000자 초과 등 유효하지 않은 요청", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}}"))),
            @ApiResponse(responseCode = "200", description = "성공", content = @Content(mediaType = "application/json", schema = @Schema(implementation = MessageResponse.class), examples = @ExampleObject(value = "{\"sender\":\"BOT\",\"content\":\"계약서와 지급 내역을 확인해 주세요.\",\"timestamp\":\"2026-10-13T10:00:00\"}"))),
            @ApiResponse(responseCode = "403", description = "본인의 채팅방이 아닙니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":403,\"code\":\"UNAUTHORIZED_CHATROOM_ACCESS\",\"message\":\"본인의 채팅방이 아닙니다.\"}}"))),
            @ApiResponse(responseCode = "404", description = "채팅방을 찾을 수 없습니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":404,\"code\":\"CHATROOM_NOT_FOUND\",\"message\":\"채팅방을 찾을 수 없습니다.\"}}"))),
            @ApiResponse(responseCode = "500", description = "GPT 응답 생성 중 오류 발생 - GPT 응답 생성 중 오류 발생", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"GPT_API_FAILURE\",\"message\":\"GPT 응답 생성 중 오류 발생 - GPT 응답 생성 중 오류 발생\"}}")))})
    public ResponseEntity<MessageResponse> ask(@Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
                                               @Parameter(description = "본인 소유 채팅방 ID", required = true, example = "42") @PathVariable Long roomId,
                                               @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "후속 질문과 이번 요청의 판례 검색 동의", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CreateMessageRequest.class), examples = { @ExampleObject(name = "일반 후속 질문", value = "{\"content\":\"기존 답변을 더 설명해주세요.\",\"allowCaseSearch\":false}"), @ExampleObject(name = "사용자가 판례 검색 승인", value = "{\"content\":\"네, 관련 판례를 찾아주세요.\",\"allowCaseSearch\":true}"), @ExampleObject(name = "사용자가 법령 근거를 변경하여 승인", value = "{\"content\":\"네, 관련 판례를 찾아주세요.\",\"allowCaseSearch\":true,\"caseSearchLawEvidenceIds\":[\"00000000-0000-0000-0000-000000000002\",\"00000000-0000-0000-0000-000000000003\"]}") })) @Valid @RequestBody CreateMessageRequest request) {
        MessageResponse bot = chatService.askQuestion(userDetails.getUser(), roomId, request);
        return ResponseEntity.ok(bot);
    }

    @PostMapping
    @Operation(summary = "질문부터 법령 수집·ETL·첫 답변까지 순차 실행", description = "content 질문 하나로 질문 유형·답변 범위 분류 → 분류별 쿼리 생성·기존 근거 검색 → LLM 충분성 판단 → 필요 시 법령 Tool 수집·MySQL 원문 저장·ETL·Qdrant 적재·재검색 → 근거 확정 → 최초 답변 생성·[[UUID]] 인용 검증 → 상담방·스냅샷·USER/BOT 저장을 동기 실행합니다. 사전 색인 API 호출은 필요하지 않습니다. 이미 근거가 충분하면 수집·ETL은 생략합니다. 빈 검색 결과는 판단 Client에 전달해 다른 조건을 검토합니다. 사실관계가 부족하면 일반 법령 안내로 범위를 제한하고 개별 허용 여부를 단정하지 않습니다. 응답 INFO는 고정 근거의 ID·출처 안내이며 중복 저장하지 않습니다. Unicode 최대 2000자·UTF-8 최대 32KiB, 인증 쿠키 또는 Bearer와 CSRF 헤더가 필요합니다. 임베딩·DB·법령 API·LLM 설정을 준비해야 합니다. 실패하면 방과 메시지를 저장하지 않지만 확보한 원문·완료 색인은 재사용을 위해 유지합니다. 진행 상태 SSE는 제공하지 않습니다.")
    @ApiResponses({@ApiResponse(responseCode = "422", description = "실행 예산 안에서 충분한 법령 근거를 확보하지 못했습니다. 방과 메시지는 저장되지 않습니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":422,\"code\":\"EVIDENCE_INSUFFICIENT\",\"message\":\"확보한 근거가 충분하지 않습니다. 질문을 구체화해 주세요.\"}}"))),
            @ApiResponse(responseCode = "503", description = "검색 의존 서비스가 비활성화/실패했거나 임베딩 대기열이 가득 찼습니다. 방과 메시지는 저장되지 않습니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = {
                @ExampleObject(name = "검색 서비스 사용 불가", value = "{\"success\":false,\"error\":{\"status\":503,\"code\":\"RETRIEVAL_UNAVAILABLE\",\"message\":\"법령 근거 검색을 사용할 수 없습니다. 잠시 후 다시 시도하세요.\"}}"),
                @ExampleObject(name = "임베딩 대기열 포화", value = "{\"success\":false,\"error\":{\"status\":503,\"code\":\"RETRIEVAL_BUSY\",\"message\":\"임베딩 처리량이 초과되었습니다. 잠시 후 다시 시도하세요.\"}}") })),
            @ApiResponse(responseCode = "200", description = "성공", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ChatRoomCreationResponse.class), examples = @ExampleObject(value = "{\"id\":42,\"messages\":[{\"sender\":\"USER\",\"content\":\"보증금을 돌려받지 못했습니다.\",\"timestamp\":\"2026-10-13T10:00:00\"},{\"sender\":\"BOT\",\"content\":\"계약서와 지급 내역을 확인해 주세요.\",\"timestamp\":\"2026-10-13T10:00:00\"},{\"sender\":\"INFO\",\"content\":\"## 관련 법령\\n주택임대차보호법\",\"timestamp\":\"2026-10-13T10:00:00\"}]}"))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청입니다. - 입력 내용 없음", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다. - 입력 내용 없음\"}}"))),
            @ApiResponse(responseCode = "500", description = "키워드 추출 실패, AI 응답 실패 또는 일반 서버 오류", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = {
@ExampleObject(name = "키워드 추출 실패", value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"KEYWORD_EXTRACTION_FAILED\",\"message\":\"키워드 추출에 실패했습니다. - 질문 분석 실패\"}}"),
@ExampleObject(name = "AI 응답 실패", value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"GPT_API_FAILURE\",\"message\":\"GPT 응답 생성 중 오류 발생 - 초기 GPT 응답 생성 실패\"}}")
}))})
    public ResponseEntity<ChatRoomCreationResponse> createAndAsk(@Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
                                                             @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "사용자 질문", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CreateChatRoomRequest.class), examples = @ExampleObject(value = "{\"content\":\"보증금을 돌려받지 못했습니다.\"}"))) @Valid @RequestBody CreateChatRoomRequest request) {
        log.info("Initial answer request path=/api/chat-rooms format=JSON state=STARTED");
        var room = chatService.createRoomAndAsk(userDetails.getUser(), request);
        log.info("Initial answer request path=/api/chat-rooms format=JSON state=COMPLETED");
        return ResponseEntity.ok(room);
    }

    @PatchMapping("/{roomId}/title")
    @Operation(summary = "내 채팅방 제목 변경", description = "본인 소유 방의 제목만 변경합니다. 앞뒤 공백 제거 후 Unicode 1~255자입니다. 메시지·근거·생성 시각은 유지합니다. Access 쿠키 또는 Bearer 인증과 X-XSRF-TOKEN 헤더가 필요합니다. 성공은 204이며 본문이 없습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "제목 변경 완료; 응답 본문 없음", content = @Content),
            @ApiResponse(responseCode = "400", description = "누락·빈 제목·길이 초과·잘못된 JSON", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}}"))),
            @ApiResponse(responseCode = "401", description = "인증 필요; Security 오류 응답", content = @Content(mediaType = "application/json", schema = @Schema(type = "object"), examples = @ExampleObject(value = "{\"error\":\"Unauthorized\"}"))),
            @ApiResponse(responseCode = "403", description = "타인 방 접근 차단. CSRF·Origin 검사 실패는 필터 응답이며 본문 형식이 다를 수 있습니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":403,\"code\":\"UNAUTHORIZED_CHATROOM_ACCESS\",\"message\":\"본인의 채팅방이 아닙니다.\"}}"))),
            @ApiResponse(responseCode = "404", description = "채팅방 없음", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":404,\"code\":\"CHATROOM_NOT_FOUND\",\"message\":\"채팅방을 찾을 수 없습니다.\"}}")))})
    public ResponseEntity<Void> changeTitle(@Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
            @Parameter(description = "본인 소유 방 ID", required = true, example = "42") @PathVariable Long roomId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = ChangeChatRoomTitleRequest.class), examples = @ExampleObject(value = "{\"title\":\"보증금 반환 상담\"}")))
            @Valid @RequestBody ChangeChatRoomTitleRequest request) {
        chatService.changeRoomTitle(user.getUser(), roomId, request.getTitle());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{roomId}")
    @Operation(summary = "내 채팅방 삭제", description = "본인 소유 채팅방과 메시지를 삭제합니다. 성공 응답에는 본문이 없습니다. CSRF 토큰 헤더가 필요합니다.")
    @ApiResponses({@ApiResponse(responseCode = "204", description = "삭제 완료; 응답 본문 없음", content = @Content),
            @ApiResponse(responseCode = "403", description = "본인의 채팅방이 아닙니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":403,\"code\":\"UNAUTHORIZED_CHATROOM_ACCESS\",\"message\":\"본인의 채팅방이 아닙니다.\"}}"))),
            @ApiResponse(responseCode = "404", description = "채팅방을 찾을 수 없습니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":404,\"code\":\"CHATROOM_NOT_FOUND\",\"message\":\"채팅방을 찾을 수 없습니다.\"}}"))),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))})
    public ResponseEntity<Void> deleteChatRoom(@Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
                                               @Parameter(description = "본인 소유 채팅방 ID", required = true, example = "42") @PathVariable Long roomId) {
        chatService.deleteChatRoom(userDetails.getUser(), roomId);
        return ResponseEntity.noContent().build();
    }
}
