package com.MoleLaw_backend.dto.request;

import lombok.Getter;
import java.util.List;

@Getter
public class BulkChatSaveRequest {
    private String title;
    private List<BulkMessage> messages;

    @Getter
    public static class BulkMessage {
        private String sender; // "USER" or "BOT"
        private String content;
    }
}
