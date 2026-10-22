package com.MoleLaw_backend.chat.service;

import com.MoleLaw_backend.chat.entity.ChatRoom;
import com.MoleLaw_backend.chat.entity.Message;
import com.MoleLaw_backend.user.entity.User;
import com.MoleLaw_backend.chat.repository.ChatRoomRepository;
import com.MoleLaw_backend.chat.repository.MessageRepository;
import com.MoleLaw_backend.chat.dto.request.CreateChatRoomRequest;
import com.MoleLaw_backend.chat.dto.request.CreateMessageRequest;
import com.MoleLaw_backend.chat.dto.response.ChatRoomResponse;
import com.MoleLaw_backend.chat.dto.response.ChatRoomCreationResponse;
import com.MoleLaw_backend.chat.dto.response.LegalAnswerResponse;
import com.MoleLaw_backend.law.dto.response.KeywordExtractionResponse;
import com.MoleLaw_backend.chat.dto.response.MessageResponse;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.law.service.analysis.KeywordExtractionService;
import com.MoleLaw_backend.chat.service.answer.LegalAnswerService;
import com.MoleLaw_backend.chat.service.answer.EvidenceRenderer;
import com.MoleLaw_backend.chat.service.evidence.ChatEvidenceService;
import com.MoleLaw_backend.chat.util.EncryptUtil;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatRoomRepository chatRoomRepository;
    private final MessageRepository messageRepository;
    private final LegalAnswerService answers;
    private final ChatTurnService turns;
    private final KeywordExtractionService extractKeyword;
    private final ChatEvidenceService evidence;
    private final EvidenceRenderer renderer;

    public List<ChatRoomResponse> getMyChatRooms(User user) {
        return getMyChatRooms(user, 0, 20);
    }

    public List<ChatRoomResponse> getMyChatRooms(User user, int page, int size) {
        requireAuthenticated(user);
        if (page < 0 || size < 1 || size > 100) {
            throw new MolelawException(ErrorCode.INVALID_REQUEST, "page는 0 이상, size는 1–100이어야 합니다");
        }
        return chatRoomRepository.findByUserOrderByCreatedAtDescIdDesc(user, PageRequest.of(page, size)).stream()
                .map(room -> {
                    String preview = messageRepository
                            .findFirstByChatRoomIdOrderByTimestampAscIdAsc(room.getId())
                            .map(m -> EncryptUtil.decrypt(m.getContent()))
                            .orElse("(메시지 없음)");
                    return ChatRoomResponse.from(room, preview);
                })
                .collect(Collectors.toList());
    }

    public List<MessageResponse> getMessages(User user, Long chatRoomId) {
        requireOwnedRoom(user, chatRoomId);
        List<MessageResponse> result = messageRepository.findByChatRoomIdOrderByTimestampAscIdAsc(chatRoomId).stream()
                .map(MessageResponse::from)
                .collect(Collectors.toList());
        // Preserve USER/BOT/INFO response order without storing a duplicate evidence body.
        int firstBot = -1;
        for (int i = 0; i < result.size(); i++) {
            if ("BOT".equals(result.get(i).getSender())) { firstBot = i; break; }
        }
        if (firstBot >= 0) {
            result.add(firstBot + 1, MessageResponse.builder().sender("INFO")
                    .content(renderer.render(evidence.load(user.getId(), chatRoomId)))
                    .timestamp(result.get(firstBot).getTimestamp()).build());
        }
        return List.copyOf(result);
    }

    public MessageResponse askQuestion(User user, Long chatRoomId, CreateMessageRequest request) {
        ChatRoom room = requireOwnedRoom(user, chatRoomId);
        ChatContentLimits.question(request == null ? null : request.getContent());

        try {
            LegalAnswerResponse answer = answers.answerFollowUp(user.getId(), chatRoomId, request.getContent());
            Message botMessage = turns.saveFollowUpTurn(room, request.getContent(), answer.getAnswer());
            return MessageResponse.from(botMessage);
        } catch (Exception e) {
            if (e instanceof MolelawException failure) throw failure;
            throw new MolelawException(ErrorCode.GPT_API_FAILURE, "GPT 응답 생성 중 오류 발생", e);
        }
    }

    public ChatRoomCreationResponse createRoomAndAsk(User user, CreateChatRoomRequest request) {
        requireAuthenticated(user);
        ChatContentLimits.question(request == null ? null : request.getContent());

        KeywordExtractionResponse keywordAndTitle;
        try {
            keywordAndTitle = extractKeyword.extractKeywords(request.getContent());
        } catch (Exception e) {
            throw new MolelawException(ErrorCode.KEYWORD_EXTRACTION_FAILED, "질문 분석 실패", e);
        }
        ChatContentLimits.title(keywordAndTitle.getSummary());

        ChatRoom chatRoom;
        try {
            var prepared = answers.prepareInitialAnswer(request.getContent());
            chatRoom = turns.saveInitialTurn(user, keywordAndTitle.getSummary(), request.getContent(), prepared);

        } catch (Exception e) {
            if (e instanceof MolelawException failure) throw failure;
            throw new MolelawException(ErrorCode.GPT_API_FAILURE, "초기 GPT 응답 생성 실패", e);
        }

        return ChatRoomCreationResponse.builder()
                .id(chatRoom.getId())
                .messages(getMessages(user, chatRoom.getId()))
                .build();
    }

    @Transactional
    public void deleteChatRoom(User user, Long chatRoomId) {
        chatRoomRepository.delete(requireOwnedRoom(user, chatRoomId));
    }

    private ChatRoom requireOwnedRoom(User user, Long roomId) {
        requireAuthenticated(user);
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new MolelawException(ErrorCode.CHATROOM_NOT_FOUND));
        if (room.getUser() == null || !user.getId().equals(room.getUser().getId())) {
            throw new MolelawException(ErrorCode.UNAUTHORIZED_CHATROOM_ACCESS);
        }
        return room;
    }

    private void requireAuthenticated(User user) {
        if (user == null || user.getId() == null) {
            throw new MolelawException(ErrorCode.USER_NOT_AUTHENTICATED);
        }
    }

}
