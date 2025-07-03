package com.MoleLaw_backend.dto.response;

import com.MoleLaw_backend.domain.entity.Message;
import com.MoleLaw_backend.util.EncryptUtil;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class MessageResponse {
    private String sender;
    private String content;
    private LocalDateTime timestamp;

    public static MessageResponse from(Message message) {
        return MessageResponse.builder()
                .sender(message.getSender().name())
                .content(EncryptUtil.decrypt(message.getContent()))
                .timestamp(message.getTimestamp())
                .build();
    }
}
