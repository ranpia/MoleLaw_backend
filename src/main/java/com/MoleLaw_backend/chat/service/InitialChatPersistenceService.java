package com.MoleLaw_backend.chat.service;

import com.MoleLaw_backend.chat.entity.ChatRoom;
import com.MoleLaw_backend.chat.entity.Message;
import com.MoleLaw_backend.chat.repository.ChatRoomRepository;
import com.MoleLaw_backend.chat.repository.MessageRepository;
import com.MoleLaw_backend.chat.service.evidence.ChatEvidenceService;
import com.MoleLaw_backend.chat.util.EncryptUtil;
import com.MoleLaw_backend.chat.service.answer.PreparedLegalAnswer;
import com.MoleLaw_backend.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** No model call in this transaction: room, evidence and initial messages commit together. */
@Service
@RequiredArgsConstructor
public class InitialChatPersistenceService {
    private final ChatRoomRepository rooms;
    private final MessageRepository messages;
    private final ChatEvidenceService evidence;

    @Transactional
    public ChatRoom save(User user, String title, String question, PreparedLegalAnswer prepared) {
        ChatRoom room = rooms.save(ChatRoom.builder().user(user).title(title).build());
        evidence.freeze(room, prepared.evidence());
        add(room, Message.Sender.USER, question);
        add(room, Message.Sender.BOT, prepared.response().getAnswer());
        add(room, Message.Sender.INFO, prepared.response().getInfo());
        return room;
    }

    private void add(ChatRoom room, Message.Sender sender, String text) {
        messages.save(Message.builder().chatRoom(room).sender(sender).content(EncryptUtil.encrypt(text)).build());
    }
}
