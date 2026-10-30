package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.chat.entity.Message;
import com.MoleLaw_backend.chat.repository.MessageRepository;
import com.MoleLaw_backend.chat.util.EncryptUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Called only after room ownership and evidence access have been checked. */
@Service
@RequiredArgsConstructor
public class CaseSearchQuestionResolver {
    private final MessageRepository messages;
    private final EncryptUtil encryptor;

    public String resolve(Long roomId, String question, boolean allowCaseSearch) {
        if (!allowCaseSearch) return question;
        var history = messages.findByChatRoomIdOrderByTimestampAscIdAsc(roomId);
        if (history.size() < 2) return question;
        var proposal = history.get(history.size() - 1);
        var request = history.get(history.size() - 2);
        if (proposal.getSender() != Message.Sender.BOT || !proposal.isCaseSearchRequested()
                || request.getSender() != Message.Sender.USER) return question;
        return encryptor.decrypt(request.getContent());
    }
}
