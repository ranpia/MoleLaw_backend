package com.MoleLaw_backend.service.chat;

import com.MoleLaw_backend.domain.entity.ChatRoom;
import com.MoleLaw_backend.domain.entity.Message;
import com.MoleLaw_backend.domain.entity.User;
import com.MoleLaw_backend.domain.repository.ChatRoomRepository;
import com.MoleLaw_backend.domain.repository.MessageRepository;
import com.MoleLaw_backend.dto.request.FirstMessageRequest;
import com.MoleLaw_backend.dto.request.MessageRequest;
import com.MoleLaw_backend.dto.response.*;
import com.MoleLaw_backend.exception.*;
import com.MoleLaw_backend.service.law.*;
import com.MoleLaw_backend.util.EncryptUtil;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatRoomRepository chatRoomRepository;
    private final MessageRepository messageRepository;
    private final FinalAnswer finalAnswer;
    private final GptService gptService;
    private final EntityManager entityManager;
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
        ChatRoom room = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new MolelawException(ErrorCode.CHATROOM_NOT_FOUND));

        if (room.getUser() == null || !room.getUser().getId().equals(user.getId())) {
            throw new MolelawException(ErrorCode.UNAUTHORIZED_CHATROOM_ACCESS);
        }

        messageRepository.save(Message.builder()
                .chatRoom(room)
                .sender(Message.Sender.USER)
                .content(EncryptUtil.encrypt(request.getContent()))
                .build());

        List<Message> messages = messageRepository.findByChatRoomIdOrderByTimestampAsc(chatRoomId);

        String firstUserQuestion = messages.stream()
                .filter(m -> m.getSender() == Message.Sender.USER)
                .map(m -> EncryptUtil.decrypt(m.getContent()))
                .findFirst()
                .orElse(request.getContent());

        try {
            AnswerResponse answer = gptService.generateAnswerWithContext(firstUserQuestion, request.getContent());

            Message botMessage = messageRepository.save(Message.builder()
                    .chatRoom(room)
                    .sender(Message.Sender.BOT)
                    .content(EncryptUtil.encrypt(answer.getAnswer()))
                    .build());
            return MessageResponse.from(botMessage);
        } catch (Exception e) {
            throw new MolelawException(ErrorCode.GPT_API_FAILURE, "GPT 응답 생성 중 오류 발생", e);
        }
    }

    public FirstMessageResponse createRoomAndAsk(User user, FirstMessageRequest request) {
        if (request == null || request.getContent().trim().isEmpty()) {
            throw new MolelawException(ErrorCode.INVALID_REQUEST, "입력 내용 없음");
        }

        KeywordAndTitleResponse keywordAndTitle;
        try {
            keywordAndTitle = extractKeyword.extractKeywords(request.getContent());
        } catch (Exception e) {
            throw new MolelawException(ErrorCode.KEYWORD_EXTRACTION_FAILED, "입력 내용: " + request.getContent(), e);
        }

        ChatRoom chatRoom = createChatRoom(user, keywordAndTitle.getSummary());

        messageRepository.save(Message.builder()
                .chatRoom(chatRoom)
                .sender(Message.Sender.USER)
                .content(EncryptUtil.encrypt(request.getContent()))
                .build());

        try {
            AnswerResponse answerResponse = finalAnswer.getAnswer(request.getContent(), keywordAndTitle.getKeywords());

            messageRepository.save(Message.builder()
                    .chatRoom(chatRoom)
                    .sender(Message.Sender.BOT)
                    .content(EncryptUtil.encrypt(answerResponse.getAnswer()))
                    .build());

            messageRepository.save(Message.builder()
                    .chatRoom(chatRoom)
                    .sender(Message.Sender.INFO)
                    .content(EncryptUtil.encrypt(answerResponse.getInfo()))
                    .build());

        } catch (Exception e) {
            throw new MolelawException(ErrorCode.GPT_API_FAILURE, "초기 GPT 응답 생성 실패", e);
        }

        return FirstMessageResponse.builder()
                .id(chatRoom.getId())
                .messages(getMessages(user, chatRoom.getId()))
                .build();
    }
}
