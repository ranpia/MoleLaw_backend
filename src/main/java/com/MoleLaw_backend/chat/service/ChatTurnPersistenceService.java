package com.MoleLaw_backend.chat.service;

import com.MoleLaw_backend.chat.entity.ChatRoom;
import com.MoleLaw_backend.chat.entity.Message;
import com.MoleLaw_backend.chat.repository.MessageRepository;
import com.MoleLaw_backend.chat.util.EncryptUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Successful follow-up pairs commit together after model execution. */
@Service
@RequiredArgsConstructor
public class ChatTurnPersistenceService {
    private final MessageRepository messages;

    @Transactional
    public Message saveSuccessfulTurn(ChatRoom room, String question, String answer) {
        messages.save(Message.builder().chatRoom(room).sender(Message.Sender.USER)
                .content(EncryptUtil.encrypt(question)).build());
        return messages.save(Message.builder().chatRoom(room).sender(Message.Sender.BOT)
                .content(EncryptUtil.encrypt(answer)).build());
    }
}
