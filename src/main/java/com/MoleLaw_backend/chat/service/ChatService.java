package com.MoleLaw_backend.chat.service;

import com.MoleLaw_backend.chat.entity.ChatRoom;
import com.MoleLaw_backend.chat.entity.Message;
import com.MoleLaw_backend.user.entity.User;
import com.MoleLaw_backend.chat.repository.ChatRoomRepository;
import com.MoleLaw_backend.chat.repository.MessageRepository;
import com.MoleLaw_backend.chat.dto.request.FirstMessageRequest;
import com.MoleLaw_backend.chat.dto.request.MessageRequest;
import com.MoleLaw_backend.chat.dto.response.ChatRoomResponse;
import com.MoleLaw_backend.chat.dto.response.FirstMessageResponse;
import com.MoleLaw_backend.chat.dto.response.GptAnswerResponse;
import com.MoleLaw_backend.law.dto.response.KeywordAndTitleResponse;
import com.MoleLaw_backend.chat.dto.response.MessageResponse;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.law.service.ExtractKeyword;
import com.MoleLaw_backend.chat.service.answer.LegalAnswerService;
import com.MoleLaw_backend.chat.util.EncryptUtil;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatRoomRepository chatRoomRepository;
    private final MessageRepository messageRepository;
    private final LegalAnswerService answers;
    private final ChatTurnService turns;
    private final ExtractKeyword extractKeyword;

    public ChatRoom createChatRoom(User user, String title) {
        requireAuthenticated(user);

        return chatRoomRepository.save(ChatRoom.builder()
                .user(user)
                .title(title)
                .build());
    }

    public List<ChatRoomResponse> getMyChatRooms(User user) {
        requireAuthenticated(user);

        return chatRoomRepository.findByUser(user).stream()
                .map(room -> {
                    String preview = messageRepository
                            .findFirstByChatRoomIdOrderByTimestampAsc(room.getId())
                            .map(m -> EncryptUtil.decrypt(m.getContent()))
                            .orElse("(메시지 없음)");
                    return ChatRoomResponse.from(room, preview);
                })
                .collect(Collectors.toList());
    }

    public List<MessageResponse> getMessages(User user, Long chatRoomId) {
        requireOwnedRoom(user, chatRoomId);

        return messageRepository.findByChatRoomIdOrderByTimestampAsc(chatRoomId).stream()
                .map(MessageResponse::from)
                .collect(Collectors.toList());
    }

    public MessageResponse askQuestion(User user, Long chatRoomId, MessageRequest request) {
        ChatRoom room = requireOwnedRoom(user, chatRoomId);

        try {
            GptAnswerResponse answer = answers.answerFollowUp(user.getId(), chatRoomId, request.getContent());
            Message botMessage = turns.saveFollowUpTurn(room, request.getContent(), answer.getAnswer());
            return MessageResponse.from(botMessage);
        } catch (Exception e) {
            throw new MolelawException(ErrorCode.GPT_API_FAILURE, "GPT 응답 생성 중 오류 발생", e);
        }
    }

    public FirstMessageResponse createRoomAndAsk(User user, FirstMessageRequest request) {
        requireAuthenticated(user);
        if (request == null || request.getContent().trim().isEmpty()) {
            throw new MolelawException(ErrorCode.INVALID_REQUEST, "입력 내용 없음");
        }

        KeywordAndTitleResponse keywordAndTitle;
        try {
            keywordAndTitle = extractKeyword.extractKeywords(request.getContent());
        } catch (Exception e) {
            throw new MolelawException(ErrorCode.KEYWORD_EXTRACTION_FAILED, "입력 내용: " + request.getContent(), e);
        }

        ChatRoom chatRoom;
        try {
            var prepared = answers.prepareInitialAnswer(request.getContent());
            chatRoom = turns.saveInitialTurn(user, keywordAndTitle.getSummary(), request.getContent(), prepared);

        } catch (Exception e) {
            throw new MolelawException(ErrorCode.GPT_API_FAILURE, "초기 GPT 응답 생성 실패", e);
        }

        return FirstMessageResponse.builder()
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
