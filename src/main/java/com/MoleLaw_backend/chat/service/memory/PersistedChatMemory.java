package com.MoleLaw_backend.chat.service.memory;

import com.MoleLaw_backend.chat.entity.ChatRoom;
import com.MoleLaw_backend.chat.entity.Message;
import com.MoleLaw_backend.chat.repository.ChatRoomRepository;
import com.MoleLaw_backend.chat.repository.MessageRepository;
import com.MoleLaw_backend.chat.util.EncryptUtil;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/** Read-only advisor history. ChatService owns persistence and room deletion. */
@Component
public class PersistedChatMemory implements ChatMemory {
    private final MessageRepository messages;
    private final ChatRoomRepository rooms;
    private final int maxMessages;

    public PersistedChatMemory(MessageRepository messages, ChatRoomRepository rooms,
                               @Value("${app.ai.memory.max-messages:20}") int maxMessages) {
        if (maxMessages < 4 || maxMessages > 100) {
            throw new IllegalArgumentException("app.ai.memory.max-messages must be between 4 and 100");
        }
        this.messages = messages;
        this.rooms = rooms;
        this.maxMessages = maxMessages;
    }

    @Override
    @Transactional(readOnly = true)
    public List<org.springframework.ai.chat.messages.Message> get(String conversationId) {
        long[] ids = parse(conversationId);
        ChatRoom room = rooms.findById(ids[1])
                .orElseThrow(() -> new MolelawException(ErrorCode.CHATROOM_NOT_FOUND));
        if (room.getUser() == null || !Long.valueOf(ids[0]).equals(room.getUser().getId())) {
            throw new MolelawException(ErrorCode.UNAUTHORIZED_CHATROOM_ACCESS);
        }
        List<Message> history = messages.findByChatRoomIdOrderByTimestampAscIdAsc(ids[1]).stream()
                .filter(message -> message.getSender() == Message.Sender.USER
                        || message.getSender() == Message.Sender.BOT)
                .sorted(Comparator.comparing(Message::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Message::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        TreeSet<Integer> positions = new TreeSet<>();
        pinFirst(history, Message.Sender.USER, positions);
        pinFirst(history, Message.Sender.BOT, positions);
        for (int i = history.size() - 1; i >= 0 && positions.size() < maxMessages; i--) {
            positions.add(i);
        }
        List<org.springframework.ai.chat.messages.Message> result = new ArrayList<>();
        for (int position : positions) {
            Message message = history.get(position);
            String content = EncryptUtil.decrypt(message.getContent());
            result.add(message.getSender() == Message.Sender.USER
                    ? new UserMessage(content) : new AssistantMessage(content));
        }
        return List.copyOf(result);
    }

    private static void pinFirst(List<Message> history, Message.Sender sender, TreeSet<Integer> positions) {
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).getSender() == sender) {
                positions.add(i);
                return;
            }
        }
    }

    private static long[] parse(String conversationId) {
        if (conversationId == null || !conversationId.matches("[1-9][0-9]*:[1-9][0-9]*")) {
            throw new MolelawException(ErrorCode.INVALID_REQUEST);
        }
        try {
            String[] parts = conversationId.split(":");
            return new long[] {Long.parseLong(parts[0]), Long.parseLong(parts[1])};
        } catch (NumberFormatException exception) {
            throw new MolelawException(ErrorCode.INVALID_REQUEST);
        }
    }

    /** Advisor callbacks must not duplicate ChatService writes. */
    @Override
    public void add(String conversationId, List<org.springframework.ai.chat.messages.Message> messages) {
    }

    /** Clearing advisor memory must never delete persisted consultation records. */
    @Override
    public void clear(String conversationId) {
    }
}
