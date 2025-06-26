package com.MoleLaw_backend.controller;

import com.MoleLaw_backend.domain.entity.User;
import com.MoleLaw_backend.dto.*;
import com.MoleLaw_backend.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat-rooms")
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    public ChatRoomResponse createRoom(@AuthenticationPrincipal User user,
                                       @RequestBody ChatRoomRequest request) {
        return chatService.createChatRoom(user, request);
    }

    @GetMapping
    public List<ChatRoomResponse> getMyRooms(@AuthenticationPrincipal User user) {
        return chatService.getMyChatRooms(user);
    }

    @GetMapping("/{roomId}")
    public List<MessageResponse> getMessages(@PathVariable Long roomId) {
        return chatService.getMessages(roomId);
    }

    @PostMapping("/{roomId}/messages")
    public void ask(@AuthenticationPrincipal User user,
                    @PathVariable Long roomId,
                    @RequestBody MessageRequest request) {
        chatService.askQuestion(user, roomId, request);
    }

    @PostMapping("/first-message")
    public List<MessageResponse> createAndAsk(@AuthenticationPrincipal User user,
                                              @RequestBody FirstMessageRequest request) {
        return chatService.createRoomAndAsk(user, request);
    }

    @Operation(summary = "프론트 메시지 배열 저장", description = "프론트에서 전달된 메시지 배열을 암호화하여 DB에 저장합니다.")
    @PostMapping("/bulk")
    public ResponseEntity<Void> saveBulk(@AuthenticationPrincipal User user,
                                         @RequestBody BulkChatSaveRequest request) {
        chatService.saveBulkMessages(user, request);
        return ResponseEntity.ok().build();
    }


}
