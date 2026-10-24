package com.MoleLaw_backend.chat.controller;

import com.MoleLaw_backend.auth.security.CustomUserDetails;
import com.MoleLaw_backend.chat.dto.request.CreateChatRoomRequest;
import com.MoleLaw_backend.chat.dto.request.LegalAnswerRequest;
import com.MoleLaw_backend.chat.dto.response.InitialAnswerStreamResponse;
import com.MoleLaw_backend.chat.service.ChatContentLimits;
import com.MoleLaw_backend.chat.service.answer.InitialAnswerStreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequiredArgsConstructor
@Tag(name = "Initial answer stream", description = "최초 질문의 실제 작업 진행·확인한 근거·답변 조각을 SSE로 전달")
public class InitialAnswerStreamController {
    private final InitialAnswerStreamService streams;
    private final com.MoleLaw_backend.chat.service.ChatService chats;

    @PostMapping(value = "/api/legal-answers/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "무저장 최초 답변 SSE", description = "Access 쿠키와 X-XSRF-TOKEN 헤더가 필요합니다. progress는 실제 작업 단계, info는 현재 채택한 근거 전체 목록(교체), answer_delta는 검증 전 임시 답변 조각입니다. completed.response로 최종 답변을 교체합니다. 저장하지 않습니다. heartbeat는 10초 간격입니다. 스트림 시작 후 실패는 HTTP 200의 error 이벤트로 전달하며 임시 답변을 폐기해야 합니다. POST fetch 스트리밍을 사용하고 자동 재연결·재실행하지 마세요.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SSE. 이벤트 data는 InitialAnswerStreamResponse JSON. completed 또는 error에서 종료", content = @Content(mediaType = "text/event-stream", schema = @Schema(implementation = InitialAnswerStreamResponse.class), examples = @ExampleObject(value = "event: progress\ndata: {\"type\":\"progress\",\"stage\":\"INITIAL_SEARCH\",\"message\":\"관련 조문을 검색하고 있습니다.\"}\n\nevent: info\ndata: {\"type\":\"info\",\"info\":\"## 고정 근거\\n\"}\n\nevent: answer_delta\ndata: {\"type\":\"answer_delta\",\"delta\":\"확인한 조문에 따르면 \"}\n\nevent: completed\ndata: {\"type\":\"completed\",\"response\":{\"answer\":\"검증된 답변\",\"info\":\"## 고정 근거\\n\"}}\n\n"))),
            @ApiResponse(responseCode = "400", description = "스트림 시작 전 질문 검증 실패", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}}")))})
    public ResponseEntity<Flux<ServerSentEvent<InitialAnswerStreamResponse>>> answer(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = LegalAnswerRequest.class), examples = @ExampleObject(value = "{\"query\":\"퇴직금 지급 요건을 알려주세요.\"}")))
            @Valid @RequestBody LegalAnswerRequest request) {
        ChatContentLimits.question(request.getQuery());
        return response(streams.stream(request.getQuery(), null));
    }

    @PostMapping(value = "/api/chat-rooms/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "상담방 생성과 최초 답변 SSE", description = "Access 쿠키와 X-XSRF-TOKEN 헤더가 필요합니다. progress → info(매번 전체 교체) → answer_delta(임시 누적) → completed 순서이며 검색 반복 시 progress/info가 반복됩니다. 검증된 답변·질문·근거를 원자적으로 저장한 후 completed.room을 반환합니다. 실패 시 error 이벤트의 공개 code/message를 표시하고 임시 답변을 폐기하세요. heartbeat는 10초 간격입니다. 연결 해제 전 이미 커밋한 저장은 취소되지 않습니다. 자동 재연결은 새 상담방을 만들 수 있으므로 금지합니다. 내부 추론이나 미채택 후보는 노출하지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SSE. completed에 최종 response와 저장된 room을 제공. 오류도 HTTP 200 error 이벤트로 전달", content = @Content(mediaType = "text/event-stream", schema = @Schema(implementation = InitialAnswerStreamResponse.class), examples = @ExampleObject(value = "event: progress\ndata: {\"type\":\"progress\",\"stage\":\"QUESTION_PLANNING\",\"message\":\"질문의 쟁점을 분석하고 있습니다.\"}\n\nevent: error\ndata: {\"type\":\"error\",\"code\":\"EVIDENCE_INSUFFICIENT\",\"message\":\"확보한 근거가 충분하지 않습니다. 질문을 구체화해 주세요.\"}\n\n"))),
            @ApiResponse(responseCode = "400", description = "스트림 시작 전 질문 검증 실패", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}}")))})
    public ResponseEntity<Flux<ServerSentEvent<InitialAnswerStreamResponse>>> createRoom(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails principal,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = CreateChatRoomRequest.class), examples = @ExampleObject(value = "{\"content\":\"퇴직금 지급 요건을 알려주세요.\"}")))
            @Valid @RequestBody CreateChatRoomRequest request) {
        ChatContentLimits.question(request.getContent());
        return response(streams.stream(request.getContent(), principal.getUser()));
    }

    private ResponseEntity<Flux<ServerSentEvent<InitialAnswerStreamResponse>>> response(Flux<ServerSentEvent<InitialAnswerStreamResponse>> body) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no").body(body);
    }

    @PostMapping(value = "/api/legal-answers/ndjson", produces = "application/x-ndjson")
    @Operation(summary = "무저장 최초 답변 NDJSON", description = "Access 쿠키와 X-XSRF-TOKEN 헤더가 필요합니다. Accept: application/x-ndjson을 전송합니다. 각 줄이 독립적인 이벤트 JSON이며 type은 progress/info/answer_delta/completed/error/heartbeat입니다. info는 전체 교체, delta는 임시 누적, completed.response는 최종 교체 값입니다. 저장하지 않습니다. 시작 후 오류는 HTTP 200 error 이벤트입니다. 10초 heartbeat, 자동 재연결·재실행 없음. SSE event/data 접두사는 없습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "JSON 객체 한 개씩 개행으로 구분해 즉시 전송. completed/error에서 종료", content = @Content(mediaType = "application/x-ndjson", schema = @Schema(implementation = InitialAnswerStreamResponse.class), examples = @ExampleObject(value = "{\"type\":\"progress\",\"stage\":\"INITIAL_SEARCH\",\"message\":\"관련 조문을 검색하고 있습니다.\"}\n{\"type\":\"info\",\"info\":\"## 고정 근거\\n\"}\n{\"type\":\"answer_delta\",\"delta\":\"확인한 조문에 따르면 \"}\n{\"type\":\"completed\",\"response\":{\"answer\":\"검증된 답변\",\"info\":\"## 고정 근거\\n\"}}\n"))),
            @ApiResponse(responseCode = "400", description = "스트림 시작 전 질문 검증 실패", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}}")))})
    public ResponseEntity<Flux<InitialAnswerStreamResponse>> answerNdjson(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = LegalAnswerRequest.class), examples = @ExampleObject(value = "{\"query\":\"퇴직금 지급 요건을 알려주세요.\"}")))
            @Valid @RequestBody LegalAnswerRequest request) {
        ChatContentLimits.question(request.getQuery());
        return ndjsonResponse(streams.stream(request.getQuery(), null, "NDJSON"));
    }

    @PostMapping(value = "/api/chat-rooms/ndjson", produces = "application/x-ndjson")
    @Operation(summary = "상담방 생성과 최초 답변 NDJSON", description = "Access 쿠키와 X-XSRF-TOKEN 헤더 및 Accept: application/x-ndjson이 필요합니다. progress/info/answer_delta/completed/error/heartbeat 이벤트를 JSON 한 줄씩 즉시 전송합니다. info는 최신 전체 근거로 교체하고 delta는 임시 표시합니다. 검증·원자적 저장 후 completed.response와 completed.room을 반환합니다. 시작 후 실패는 HTTP 200 error 이벤트이며 임시 답변을 폐기해야 합니다. 10초 heartbeat를 제공하고 내부 추론은 공개하지 않습니다. 연결 종료 전 커밋한 저장은 유지됩니다. 자동 재시도는 중복 방을 만들 수 있어 금지합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "진행과 답변을 줄 단위 JSON으로 전송. completed에 최종 답변·저장된 방 제공", content = @Content(mediaType = "application/x-ndjson", schema = @Schema(implementation = InitialAnswerStreamResponse.class), examples = @ExampleObject(value = "{\"type\":\"progress\",\"stage\":\"QUESTION_PLANNING\",\"message\":\"질문의 쟁점을 분석하고 있습니다.\"}\n{\"type\":\"error\",\"code\":\"EVIDENCE_INSUFFICIENT\",\"message\":\"확보한 근거가 충분하지 않습니다. 질문을 구체화해 주세요.\"}\n"))),
            @ApiResponse(responseCode = "400", description = "스트림 시작 전 질문 검증 실패", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}}")))})
    public ResponseEntity<Flux<InitialAnswerStreamResponse>> createRoomNdjson(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails principal,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = CreateChatRoomRequest.class), examples = @ExampleObject(value = "{\"content\":\"퇴직금 지급 요건을 알려주세요.\"}")))
            @Valid @RequestBody CreateChatRoomRequest request) {
        ChatContentLimits.question(request.getContent());
        return ndjsonResponse(streams.stream(request.getContent(), principal.getUser(), "NDJSON"));
    }

    private ResponseEntity<Flux<InitialAnswerStreamResponse>> ndjsonResponse(Flux<ServerSentEvent<InitialAnswerStreamResponse>> body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_NDJSON).header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no").body(body.map(ServerSentEvent::data));
    }

    @PostMapping(value = "/api/chat-rooms/{roomId}/messages/ndjson", produces = "application/x-ndjson")
    @Operation(summary = "후속 상담 답변 NDJSON", description = "Access 쿠키와 X-XSRF-TOKEN 헤더 및 Accept: application/x-ndjson이 필요합니다. 본인 소유 방만 허용하고 이전 대화·확정 근거를 복원합니다. progress/info/answer_delta/completed/error/heartbeat를 JSON 한 줄씩 전송합니다. info는 전체 교체, delta는 내부 인용 UUID를 근거 번호로 바꾼 임시 텍스트이며 완료 후 response.answer로 교체합니다. 최종 원문 인용 검증 후 질문·답변·새 인용 판례만 원자적으로 저장하고 completed.messageResult에 BOT 메시지와 동의 요청 여부를 제공합니다. allowCaseSearch는 이번 요청에 대한 명시적 동의이며 기본 false입니다. 동의 전 외부 판례 검색은 차단하고 기존 판례만 로컬 읽기 가능합니다. 후속 법령 수집·임베딩은 하지 않습니다. 동의 시 기본 본문 범위에서 검색하고 LLM이 사건명 또는 참조법령 범위를 선택할 수 있습니다. 법률용어 변환 후 선택 범위 전체 페이지를 수집하며 미완료 여부를 구분합니다. 기본 최대 4건 본문 발췌를 리랭크해 상위 2건만 읽기 후보로 제공합니다. 검토 단계는 progress/info만 보내고 도구 없는 최종 작성 단계만 답변 조각으로 전송합니다. 시작 후 실패는 HTTP 200 error 이벤트이며 임시 답변을 폐기하세요. 자동 재실행 금지. 커밋 후 연결 종료는 저장을 취소하지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "후속 답변 NDJSON. 완료 시 response와 messageResult 제공", content = @Content(mediaType = "application/x-ndjson", schema = @Schema(implementation = InitialAnswerStreamResponse.class), examples = @ExampleObject(value = "{\"type\":\"progress\",\"stage\":\"CONVERSATION_LOADING\",\"message\":\"이전 대화와 상담방 근거를 확인하고 있습니다.\"}\n{\"type\":\"completed\",\"response\":{\"answer\":\"검증된 후속 답변\",\"info\":\"\"},\"messageResult\":{\"sender\":\"BOT\",\"content\":\"검증된 후속 답변\",\"timestamp\":\"2026-10-24T09:00:00\",\"caseSearchRequested\":false}}\n"))),
            @ApiResponse(responseCode = "400", description = "유효하지 않은 질문", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다.\"}}"))),
            @ApiResponse(responseCode = "403", description = "타인의 방 또는 CSRF·Origin 오류", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":403,\"code\":\"UNAUTHORIZED_CHATROOM_ACCESS\",\"message\":\"본인의 채팅방이 아닙니다.\"}}"))),
            @ApiResponse(responseCode = "404", description = "존재하지 않는 방", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":404,\"code\":\"CHATROOM_NOT_FOUND\",\"message\":\"채팅방을 찾을 수 없습니다.\"}}")))})
    public ResponseEntity<Flux<InitialAnswerStreamResponse>> followUpNdjson(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails principal,
            @Parameter(description = "본인 소유 상담방 ID", required = true, example = "42") @org.springframework.web.bind.annotation.PathVariable Long roomId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = com.MoleLaw_backend.chat.dto.request.CreateMessageRequest.class), examples = @ExampleObject(value = "{\"content\":\"그 조문의 적용 요건을 더 설명해 주세요.\",\"allowCaseSearch\":false}")))
            @Valid @RequestBody com.MoleLaw_backend.chat.dto.request.CreateMessageRequest request) {
        chats.validateFollowUpAccess(principal.getUser(), roomId, request.getContent());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_NDJSON).header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no").body(streams.followUp(request.getContent(), principal.getUser(), roomId, request.isAllowCaseSearch()));
    }
}
