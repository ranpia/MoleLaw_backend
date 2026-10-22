package com.MoleLaw_backend.chat.service;

import com.MoleLaw_backend.chat.entity.ChatRoom;
import com.MoleLaw_backend.chat.entity.Message;
import com.MoleLaw_backend.chat.repository.ChatRoomRepository;
import com.MoleLaw_backend.chat.repository.MessageRepository;
import com.MoleLaw_backend.chat.dto.answer.PreparedLegalAnswer;
import com.MoleLaw_backend.chat.service.evidence.ChatEvidenceService;
import com.MoleLaw_backend.chat.util.EncryptUtil;
import com.MoleLaw_backend.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Successful turns commit atomically. Model calls must finish before entering this service. */
@Service
@RequiredArgsConstructor
public class ChatTurnService {
    private final ChatRoomRepository rooms;
    private final MessageRepository messages;
    private final ChatEvidenceService evidence;

    @Transactional
    public ChatRoom saveInitialTurn(User user, String title, String question, PreparedLegalAnswer prepared) {
        ChatContentLimits.title(title);
        ChatContentLimits.question(question);
        ChatContentLimits.answer(prepared.response().getAnswer());
        ChatRoom room = rooms.save(ChatRoom.builder().user(user).title(title).build());
        evidence.freeze(room, prepared.evidence());
        add(room, Message.Sender.USER, question);
        add(room, Message.Sender.BOT, prepared.response().getAnswer());
        return room;
    }

    @Transactional
    public Message saveFollowUpTurn(ChatRoom room, String question, String answer) {
        ChatContentLimits.question(question);
        ChatContentLimits.answer(answer);
        add(room, Message.Sender.USER, question);
        return add(room, Message.Sender.BOT, answer);
    }

    private Message add(ChatRoom room, Message.Sender sender, String text) {
        return messages.save(Message.builder().chatRoom(room).sender(sender).content(EncryptUtil.encrypt(text)).build());
    }
}
