package com.MoleLaw_backend.service;

import com.MoleLaw_backend.entity.ChatRoom;
import com.MoleLaw_backend.entity.Message;
import com.MoleLaw_backend.dao.ChatRoomRepository;
import com.MoleLaw_backend.dao.MessageRepository;
import com.MoleLaw_backend.dto.answer.PreparedLegalAnswer;
import com.MoleLaw_backend.dto.answer.PreparedFollowUpAnswer;
import com.MoleLaw_backend.service.evidence.ChatCaseEvidenceService;
import com.MoleLaw_backend.service.evidence.ChatEvidenceService;
import com.MoleLaw_backend.util.EncryptUtil;
import com.MoleLaw_backend.entity.User;
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
    private final EncryptUtil encryptor;
    private final ChatCaseEvidenceService cases;
    private final com.MoleLaw_backend.dao.UserRepository users;

    @Transactional
    public ChatRoom saveInitialTurn(User user, String title, String question, PreparedLegalAnswer prepared) {
        ChatContentLimits.title(title);
        ChatContentLimits.question(question);
        ChatContentLimits.answer(prepared.response().getAnswer());
        var activeUser = users.findByIdForUpdate(user.getId()).orElseThrow(() ->
                new com.MoleLaw_backend.util.exception.MolelawException(
                        com.MoleLaw_backend.util.exception.ErrorCode.USER_NOT_AUTHENTICATED));
        ChatRoom room = rooms.save(ChatRoom.builder().user(activeUser).title(title).build());
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

    @Transactional
    public Message savePreparedFollowUpTurn(ChatRoom room, String question, PreparedFollowUpAnswer prepared) {
        ChatContentLimits.question(question);
        ChatContentLimits.answer(prepared.response().getAnswer());
        cases.append(room, prepared.newEvidence());
        if (!prepared.newEvidence().isEmpty()) evidence.load(room.getUser().getId(), room.getId());
        add(room, Message.Sender.USER, question, false, prepared.caseSearchConsent());
        return add(room, Message.Sender.BOT, prepared.response().getAnswer(), prepared.caseSearchRequested(), false);
    }

    private Message add(ChatRoom room, Message.Sender sender, String text) {
        return add(room, sender, text, false, false);
    }

    private Message add(ChatRoom room, Message.Sender sender, String text, boolean requested, boolean consent) {
        return messages.save(Message.builder().chatRoom(room).sender(sender).content(encryptor.encrypt(text))
                .caseSearchRequested(requested).caseSearchConsent(consent).build());
    }
}
