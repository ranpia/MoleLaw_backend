package com.MoleLaw_backend.chat.service.memory;

import com.MoleLaw_backend.chat.util.TestEncryption;

import com.MoleLaw_backend.chat.entity.ChatRoom;
import com.MoleLaw_backend.chat.entity.Message;
import com.MoleLaw_backend.chat.repository.ChatRoomRepository;
import com.MoleLaw_backend.chat.repository.MessageRepository;
import com.MoleLaw_backend.chat.util.EncryptUtil;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PersistedChatMemoryTest {
    private MessageRepository messages;
    private ChatRoomRepository rooms;
    private PersistedChatMemory memory;

    @BeforeEach
    void setUp() {
        messages = mock(MessageRepository.class);
        rooms = mock(ChatRoomRepository.class);
        memory = new PersistedChatMemory(messages, rooms, 4, TestEncryption.create());
    }

    @Test
    void checksOwnershipBeforeReadingMessages() {
        when(rooms.findById(2L)).thenReturn(Optional.of(ChatRoom.builder()
                .id(2L).user(User.builder().id(9L).build()).build()));
        assertThatThrownBy(() -> memory.get("1:2"))
                .isInstanceOfSatisfying(MolelawException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED_CHATROOM_ACCESS));
        verifyNoInteractions(messages);
    }

    @Test
    void missingRoomDoesNotReadHistory() {
        when(rooms.findById(2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> memory.get("1:2"))
                .isInstanceOfSatisfying(MolelawException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.CHATROOM_NOT_FOUND));
        verifyNoInteractions(messages);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"2", "0:2", "1:-2", "1:2:3", "01:2", "1:9223372036854775808"})
    void rejectsInvalidServerConversationIds(String id) {
        assertThatThrownBy(() -> memory.get(id)).isInstanceOf(MolelawException.class);
        verifyNoInteractions(rooms, messages);
    }

    @Test
    void decryptsRolesAndExcludesInfoWithoutDuplicatingPinnedHistory() {
        ownRoom();
        when(messages.findByChatRoomIdOrderByTimestampAscIdAsc(2L)).thenReturn(List.of(
                message(Message.Sender.USER, "question"), message(Message.Sender.BOT, "answer"),
                message(Message.Sender.INFO, "private metadata")));
        var history = memory.get("1:2");
        assertThat(history).hasSize(2);
        assertThat(history.get(0)).isInstanceOf(UserMessage.class);
        assertThat(history.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(history).extracting(org.springframework.ai.chat.messages.Message::getText)
                .containsExactly("question", "answer");
    }

    @Test
    void pinsFirstTurnAndKeepsLatestMessagesInChronologicalOrder() {
        ownRoom();
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            history.add(message(i % 2 == 0 ? Message.Sender.USER : Message.Sender.BOT, "message " + i));
        }
        when(messages.findByChatRoomIdOrderByTimestampAscIdAsc(2L)).thenReturn(history);
        assertThat(memory.get("1:2")).extracting(org.springframework.ai.chat.messages.Message::getText)
                .containsExactly("message 0", "message 1", "message 8", "message 9");
    }

    @Test
    void advisorWriteAndClearCallbacksNeverModifyPersistence() {
        memory.add("1:2", List.of(new UserMessage("new question")));
        memory.clear("1:2");
        verifyNoInteractions(rooms, messages);
    }

    @Test
    void rejectsUnboundedConfiguration() {
        assertThatThrownBy(() -> new PersistedChatMemory(messages, rooms, 3, TestEncryption.create()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PersistedChatMemory(messages, rooms, 101, TestEncryption.create()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void ownRoom() {
        when(rooms.findById(2L)).thenReturn(Optional.of(ChatRoom.builder()
                .id(2L).user(User.builder().id(1L).build()).build()));
    }

    private static Message message(Message.Sender sender, String content) {
        return Message.builder().sender(sender).content(TestEncryption.create().encrypt(content)).build();
    }
}
