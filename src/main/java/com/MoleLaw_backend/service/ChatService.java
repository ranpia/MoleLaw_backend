package com.MoleLaw_backend.service;

import com.MoleLaw_backend.domain.entity.ChatRoom;
import com.MoleLaw_backend.domain.entity.Message;
import com.MoleLaw_backend.domain.entity.User;
import com.MoleLaw_backend.domain.repository.ChatRoomRepository;
import com.MoleLaw_backend.domain.repository.MessageRepository;
import com.MoleLaw_backend.dto.*;
import com.MoleLaw_backend.dto.response.AnswerResponse;
import com.MoleLaw_backend.service.law.FinalAnswer;
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
    /**
     * 🔸 채팅방 생성
     */
    public ChatRoom createChatRoom(User user, String title) {
        System.out.println("🔥 user 객체: " + user); // toString 재정의 안 했으면 클래스명@해시
        System.out.println("🔥 user ID: " + (user != null ? user.getId() : "null"));

        ChatRoom chatRoom = ChatRoom.builder()
                .user(user)
                .title(title)
                .build();

        return chatRoomRepository.save(chatRoom);
    }


    /**
     * 🔸 사용자 채팅방 전체 목록 조회 (첫 메시지 복호화 포함)
     */
    public List<ChatRoomResponse> getMyChatRooms(User user) {
        return chatRoomRepository.findByUser(user).stream()
                .map(room -> {
                    Message firstMessage = messageRepository
                            .findFirstByChatRoomIdOrderByTimestampAsc(room.getId())
                            .orElse(null);

                    String preview = firstMessage != null
                            ? EncryptUtil.decrypt(firstMessage.getContent())
                            : "(메시지 없음)";

                    return ChatRoomResponse.from(room, preview);
                })
                .collect(Collectors.toList());
    }

    /**
     * 🔸 특정 채팅방 메시지 목록 조회 (복호화 포함)
     */
    public List<MessageResponse> getMessages(Long chatRoomId) {
        return messageRepository.findByChatRoomIdOrderByTimestampAsc(chatRoomId)
                .stream()
                .map(MessageResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * 🔸 기존 채팅방에 질문 → GPT 응답 저장
     */
    public void askQuestion(User user, Long chatRoomId, MessageRequest request) {
        ChatRoom room = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new IllegalArgumentException("채팅방이 존재하지 않습니다."));

        // 질문 저장
        messageRepository.save(Message.builder()
                .chatRoom(room)
                .sender(Message.Sender.USER)
                .content(EncryptUtil.encrypt(request.getContent()))
                .build());

        // GPT 응답 생성 + 저장

        AnswerResponse answer;
        // 첫 질문 / 후속 질문 구분
        if (messageRepository.countByChatRoomId(chatRoomId) == 1) {
            answer = finalAnswer.getAnswer(request.getContent());
        } else {
            answer = gptService.generateAnswer(request.getContent());
        }


        messageRepository.save(Message.builder()
                .chatRoom(room)
                .sender(Message.Sender.BOT)
                .content(EncryptUtil.encrypt(answer.getAnswer()))
                .build());
    }

    /**
     * ✅ 새로운 채팅방 생성 + GPT로 제목 + 답변 동시 생성 + 저장
     */
    public List<MessageResponse> createRoomAndAsk(User user, FirstMessageRequest request) {
        // 1. GPT로 제목 생성
        String title;
        try {
            title = gptService.generateTitle(request.getContent());
        } catch (Exception e) {
            title = "제목 없음";
        }

        // 2. ChatRoom 생성 (userId만으로 프록시 연결)
        ChatRoom chatRoom = createChatRoom(user, title);  // ⬅️ Entity 반환 메서드

        // 3. 사용자 메시지 저장
        messageRepository.save(Message.builder()
                .chatRoom(chatRoom)  // ✅ 반드시 ChatRoom 엔티티
                .sender(Message.Sender.USER)
                .content(EncryptUtil.encrypt(request.getContent()))
                .build());

        // 4. GPT 응답 저장
        AnswerResponse answerResponse = finalAnswer.getAnswer(request.getContent());
        String combined = "답변:\n" + answerResponse.getAnswer() + "\n\n관련 정보:\n" + answerResponse.getInfo();

        messageRepository.save(Message.builder()
                .chatRoom(chatRoom)
                .sender(Message.Sender.BOT)
                .content(EncryptUtil.encrypt(combined))
                .build());

        // 5. 메시지 목록 반환
        return getMessages(chatRoom.getId());
    }


}
