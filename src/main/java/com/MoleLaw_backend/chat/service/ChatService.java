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
import com.MoleLaw_backend.chat.service.answer.InitialAnswerService;
import com.MoleLaw_backend.chat.service.answer.FollowUpAnswerService;
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
    private final InitialAnswerService initialAnswers;
    private final FollowUpAnswerService followUpAnswers;
    private final ChatTurnPersistenceService turnPersistence;
    private final InitialChatPersistenceService initialPersistence;
    private final ExtractKeyword extractKeyword;

    public ChatRoom createChatRoom(User user, String title) {
        if (user == null || user.getId() == null) {
            throw new MolelawException(ErrorCode.USER_NOT_AUTHENTICATED);
        }

        return chatRoomRepository.save(ChatRoom.builder()
                .user(user)
                .title(title)
                .build());
    }

    public List<ChatRoomResponse> getMyChatRooms(User user) {
        if (user == null || user.getId() == null) {
            throw new MolelawException(ErrorCode.USER_NOT_AUTHENTICATED);
        }

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
        ChatRoom room = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new MolelawException(ErrorCode.CHATROOM_NOT_FOUND));

        if (room.getUser() == null || !room.getUser().getId().equals(user.getId())) {
            throw new MolelawException(ErrorCode.UNAUTHORIZED_CHATROOM_ACCESS);
        }

        return messageRepository.findByChatRoomIdOrderByTimestampAsc(chatRoomId).stream()
                .map(MessageResponse::from)
                .collect(Collectors.toList());
    }

    public MessageResponse askQuestion(User user, Long chatRoomId, MessageRequest request) {
        if (user == null || user.getId() == null) {
            throw new MolelawException(ErrorCode.USER_NOT_AUTHENTICATED);
        }
        ChatRoom room = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new MolelawException(ErrorCode.CHATROOM_NOT_FOUND));

        if (room.getUser() == null || !room.getUser().getId().equals(user.getId())) {
            throw new MolelawException(ErrorCode.UNAUTHORIZED_CHATROOM_ACCESS);
        }

        try {
            GptAnswerResponse answer = followUpAnswers.generateAnswerWithContext(user.getId(), chatRoomId, request.getContent());
            Message botMessage = turnPersistence.saveSuccessfulTurn(room, request.getContent(), answer.getAnswer());
            return MessageResponse.from(botMessage);
        } catch (Exception e) {
            throw new MolelawException(ErrorCode.GPT_API_FAILURE, "GPT 응답 생성 중 오류 발생", e);
        }
    }

    public FirstMessageResponse createRoomAndAsk(User user, FirstMessageRequest request) {
        if (user == null || user.getId() == null) throw new MolelawException(ErrorCode.USER_NOT_AUTHENTICATED);
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
            var prepared = initialAnswers.prepareAnswer(request.getContent());
            chatRoom = initialPersistence.save(user, keywordAndTitle.getSummary(), request.getContent(), prepared);

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
        try {
            System.out.println("🧹 [삭제 시작] 사용자 ID: " + user.getId() + ", 채팅방 ID: " + chatRoomId);

            ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                    .orElseThrow(() -> new MolelawException(ErrorCode.CHATROOM_NOT_FOUND));

            System.out.println("🔍 채팅방 소유자 ID: " + (chatRoom.getUser() != null ? chatRoom.getUser().getId() : "null"));
            System.out.println("📦 메시지 개수: " + chatRoom.getMessages().size());

            if (chatRoom.getUser() == null || !chatRoom.getUser().getId().equals(user.getId())) {
                throw new MolelawException(ErrorCode.UNAUTHORIZED_CHATROOM_ACCESS);
            }

            chatRoomRepository.delete(chatRoom);
            System.out.println("✅ 삭제 완료");

        } catch (Exception e) {
            System.out.println("❌ 예외 발생:");
            e.printStackTrace(); // ✅ 콘솔에 전체 예외 출력
            throw e; // 다시 던져서 글로벌 핸들러에서 처리되게
        }
    }


}
