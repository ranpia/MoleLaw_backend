package com.MoleLaw_backend.chat.controller;

import com.MoleLaw_backend.chat.dto.request.FirstMessageRequest;
import com.MoleLaw_backend.chat.dto.request.MessageRequest;
import com.MoleLaw_backend.chat.dto.response.ChatRoomResponse;
import com.MoleLaw_backend.chat.dto.response.FirstMessageResponse;
import com.MoleLaw_backend.chat.dto.response.MessageResponse;
import com.MoleLaw_backend.chat.service.ChatService;
import com.MoleLaw_backend.auth.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Chat", description = "채팅 관련 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat-rooms")
public class ChatController {

    private final ChatService chatService;

    @GetMapping
    @Operation(summary = "내 채팅방 목록 조회", description = "본인 소유 채팅방 배열을 반환합니다. preview는 최초 메시지이며 메시지가 없으면 (메시지 없음)입니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "성공", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ChatRoomResponse.class)), examples = @ExampleObject(value = "[{\"id\":42,\"title\":\"보증금 상담\",\"preview\":\"보증금을 돌려받지 못했습니다.\",\"createdAt\":\"2026-10-13T10:00:00\"}]"))),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))})
    public List<ChatRoomResponse> getMyRooms(@Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails) {
        return chatService.getMyChatRooms(userDetails.getUser());
    }

    @GetMapping("/{roomId}")
    @Operation(summary = "채팅방 메시지 전체 조회", description = "본인 소유 채팅방의 복호화된 메시지를 시간순으로 반환합니다. sender는 USER, BOT, INFO이며 INFO는 법령 및 판례 마크다운입니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "성공", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = MessageResponse.class)), examples = @ExampleObject(value = "[{\"sender\":\"BOT\",\"content\":\"계약서와 지급 내역을 확인해 주세요.\",\"timestamp\":\"2026-10-13T10:00:00\"}]"))),
            @ApiResponse(responseCode = "403", description = "본인의 채팅방이 아닙니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":403,\"code\":\"UNAUTHORIZED_CHATROOM_ACCESS\",\"message\":\"본인의 채팅방이 아닙니다.\"}}"))),
            @ApiResponse(responseCode = "404", description = "채팅방을 찾을 수 없습니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":404,\"code\":\"CHATROOM_NOT_FOUND\",\"message\":\"채팅방을 찾을 수 없습니다.\"}}"))),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))})
    public List<MessageResponse> getMessages(@Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
                                             @Parameter(description = "본인 소유 채팅방 ID", required = true, example = "42") @PathVariable Long roomId) {
        return chatService.getMessages(userDetails.getUser(), roomId);
    }

    @PostMapping("/{roomId}/messages")
    @Operation(summary = "기존 채팅방에 질문 추가", description = "본인 소유 채팅방에 사용자 메시지를 저장하고 AI 답변 한 건을 반환합니다. JSON content와 CSRF 토큰 헤더를 전달합니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "성공", content = @Content(mediaType = "application/json", schema = @Schema(implementation = MessageResponse.class), examples = @ExampleObject(value = "{\"sender\":\"BOT\",\"content\":\"계약서와 지급 내역을 확인해 주세요.\",\"timestamp\":\"2026-10-13T10:00:00\"}"))),
            @ApiResponse(responseCode = "403", description = "본인의 채팅방이 아닙니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":403,\"code\":\"UNAUTHORIZED_CHATROOM_ACCESS\",\"message\":\"본인의 채팅방이 아닙니다.\"}}"))),
            @ApiResponse(responseCode = "404", description = "채팅방을 찾을 수 없습니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":404,\"code\":\"CHATROOM_NOT_FOUND\",\"message\":\"채팅방을 찾을 수 없습니다.\"}}"))),
            @ApiResponse(responseCode = "500", description = "GPT 응답 생성 중 오류 발생 - GPT 응답 생성 중 오류 발생", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"GPT_API_FAILURE\",\"message\":\"GPT 응답 생성 중 오류 발생 - GPT 응답 생성 중 오류 발생\"}}")))})
    public ResponseEntity<MessageResponse> ask(@Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
                                               @Parameter(description = "본인 소유 채팅방 ID", required = true, example = "42") @PathVariable Long roomId,
                                               @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "사용자 질문", content = @Content(mediaType = "application/json", schema = @Schema(implementation = MessageRequest.class), examples = @ExampleObject(value = "{\"content\":\"보증금을 돌려받지 못했습니다.\"}"))) @RequestBody MessageRequest request) {
        MessageResponse bot = chatService.askQuestion(userDetails.getUser(), roomId, request);
        return ResponseEntity.ok(bot);
    }

    @PostMapping("/first-message")
    @Operation(summary = "채팅방 생성 및 첫 질문", description = "공백이 아닌 content로 채팅방을 생성하고 USER 질문, BOT 답변, INFO 법령 및 판례 마크다운을 반환합니다. CSRF 토큰 헤더가 필요합니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "성공", content = @Content(mediaType = "application/json", schema = @Schema(implementation = FirstMessageResponse.class), examples = @ExampleObject(value = "{\"id\":42,\"messages\":[{\"sender\":\"USER\",\"content\":\"보증금을 돌려받지 못했습니다.\",\"timestamp\":\"2026-10-13T10:00:00\"},{\"sender\":\"BOT\",\"content\":\"계약서와 지급 내역을 확인해 주세요.\",\"timestamp\":\"2026-10-13T10:00:00\"},{\"sender\":\"INFO\",\"content\":\"## 관련 법령\\n주택임대차보호법\",\"timestamp\":\"2026-10-13T10:00:00\"}]}"))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청입니다. - 입력 내용 없음", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":400,\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청입니다. - 입력 내용 없음\"}}"))),
            @ApiResponse(responseCode = "500", description = "키워드 추출 실패, AI 응답 실패 또는 일반 서버 오류", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = {
@ExampleObject(name = "키워드 추출 실패", value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"KEYWORD_EXTRACTION_FAILED\",\"message\":\"키워드 추출에 실패했습니다. - 입력 내용: 보증금을 돌려받지 못했습니다.\"}}"),
@ExampleObject(name = "AI 응답 실패", value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"GPT_API_FAILURE\",\"message\":\"GPT 응답 생성 중 오류 발생 - 초기 GPT 응답 생성 실패\"}}")
}))})
    public ResponseEntity<FirstMessageResponse> createAndAsk(@Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
                                                             @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "사용자 질문", content = @Content(mediaType = "application/json", schema = @Schema(implementation = FirstMessageRequest.class), examples = @ExampleObject(value = "{\"content\":\"보증금을 돌려받지 못했습니다.\"}"))) @RequestBody FirstMessageRequest request) {
        return ResponseEntity.ok(chatService.createRoomAndAsk(userDetails.getUser(), request));
    }

    @DeleteMapping("/{chatRoomId}")
    @Operation(summary = "내 채팅방 삭제", description = "본인 소유 채팅방과 메시지를 삭제합니다. 성공 응답에는 본문이 없습니다. CSRF 토큰 헤더가 필요합니다.")
    @ApiResponses({@ApiResponse(responseCode = "204", description = "삭제 완료; 응답 본문 없음", content = @Content),
            @ApiResponse(responseCode = "403", description = "본인의 채팅방이 아닙니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":403,\"code\":\"UNAUTHORIZED_CHATROOM_ACCESS\",\"message\":\"본인의 채팅방이 아닙니다.\"}}"))),
            @ApiResponse(responseCode = "404", description = "채팅방을 찾을 수 없습니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":404,\"code\":\"CHATROOM_NOT_FOUND\",\"message\":\"채팅방을 찾을 수 없습니다.\"}}"))),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류입니다.", content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/BusinessErrorResponse"), examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류입니다.\"}}")))})
    public ResponseEntity<Void> deleteChatRoom(@Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
                                               @Parameter(description = "본인 소유 채팅방 ID", required = true, example = "42") @PathVariable Long chatRoomId) {
        chatService.deleteChatRoom(userDetails.getUser(), chatRoomId);
        return ResponseEntity.noContent().build();
    }
}
