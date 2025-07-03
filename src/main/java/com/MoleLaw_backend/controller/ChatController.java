package com.MoleLaw_backend.controller;

import com.MoleLaw_backend.dto.*;
import com.MoleLaw_backend.service.ChatService;
import com.MoleLaw_backend.service.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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
    @Operation(summary = "사용자의 채팅방 전체 리스트 조회", description = "채팅방 id로 구분짓고 json배열 형식으로 제목, 미리보기, 생성일 표기")
    public List<ChatRoomResponse> getMyRooms(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return chatService.getMyChatRooms(userDetails.getUser());
    }

    @GetMapping("/{roomId}")
    @Operation(summary = "채팅방 id 기반 채팅방 내 채팅 전체 조회", description = "sender로 구분 content에 내용")
    public List<MessageResponse> getMessages(@AuthenticationPrincipal CustomUserDetails userDetails,
                                             @PathVariable Long roomId) {
        return chatService.getMessages(userDetails.getUser(), roomId);
    }

    @PostMapping("/{roomId}/messages")
    @Operation(summary = "해당 id 채팅방에 새로운 유저 질문 추가 및 답변", description = "sender로 구분지으며 생성일 기반 오름차순 조회")
    public void ask(@AuthenticationPrincipal CustomUserDetails userDetails,
                    @PathVariable Long roomId,
                    @RequestBody MessageRequest request) {
        chatService.askQuestion(userDetails.getUser(), roomId, request);
    }

    @PostMapping("/first-message")
    @Operation(summary = "채팅방 생성 및 첫 질문 받는 컨트롤러", description = "유저 메시지와 봇의 메시지는 sender로 구분짓고 md파일 형식은 sender: info 형식으로 제공 ")
    public List<MessageResponse> createAndAsk(@AuthenticationPrincipal CustomUserDetails userDetails,
                                              @RequestBody FirstMessageRequest request) {
        return chatService.createRoomAndAsk(userDetails.getUser(), request);
    }
}
