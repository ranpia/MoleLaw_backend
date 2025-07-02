package com.MoleLaw_backend.controller;

import com.MoleLaw_backend.dto.*;
import com.MoleLaw_backend.service.ChatService;
import com.MoleLaw_backend.service.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat-rooms")
public class ChatController {

    private final ChatService chatService;

    @GetMapping
    public List<ChatRoomResponse> getMyRooms(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return chatService.getMyChatRooms(userDetails.getUser());
    }

    @GetMapping("/{roomId}")
    public List<MessageResponse> getMessages(@AuthenticationPrincipal CustomUserDetails userDetails,
                                             @PathVariable Long roomId) {
        return chatService.getMessages(userDetails.getUser(), roomId);
    }

    @PostMapping("/{roomId}/messages")
    public void ask(@AuthenticationPrincipal CustomUserDetails userDetails,
                    @PathVariable Long roomId,
                    @RequestBody MessageRequest request) {
        chatService.askQuestion(userDetails.getUser(), roomId, request);
    }

    @PostMapping("/first-message")
    public List<MessageResponse> createAndAsk(@AuthenticationPrincipal CustomUserDetails userDetails,
                                              @RequestBody FirstMessageRequest request) {
        return chatService.createRoomAndAsk(userDetails.getUser(), request);
    }
}
