package com.MoleLaw_backend.service;

import com.MoleLaw_backend.util.TestEncryption;

import com.MoleLaw_backend.dto.request.CreateChatRoomRequest;
import com.MoleLaw_backend.entity.ChatRoom;
import com.MoleLaw_backend.entity.Message;
import com.MoleLaw_backend.dao.ChatRoomRepository;
import com.MoleLaw_backend.dao.MessageRepository;
import com.MoleLaw_backend.service.answer.EvidenceRenderer;
import com.MoleLaw_backend.service.answer.LegalAnswerService;
import com.MoleLaw_backend.service.evidence.ChatEvidenceService;
import com.MoleLaw_backend.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.util.EncryptUtil;
import com.MoleLaw_backend.util.exception.MolelawException;
import com.MoleLaw_backend.service.analysis.KeywordExtractionService;
import com.MoleLaw_backend.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatStorageServiceTest {
    private final ChatRoomRepository rooms = mock(ChatRoomRepository.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final ChatEvidenceService evidence = mock(ChatEvidenceService.class);
    private final LegalAnswerService answers = mock(LegalAnswerService.class);
    private final KeywordExtractionService extractor = mock(KeywordExtractionService.class);
    private final ChatService service = new ChatService(rooms, messages, answers, mock(ChatTurnService.class),
            extractor, evidence, new EvidenceRenderer(), TestEncryption.create());
    private final User user = User.builder().id(1L).build();
    private final ChatRoom room = ChatRoom.builder().id(2L).user(user).title("상담").build();

    @Test
    void synthesizesOneInfoAfterFirstBotFromSnapshotWithoutWritingMessages() {
        when(rooms.findById(2L)).thenReturn(Optional.of(room));
        LocalDateTime timestamp = LocalDateTime.of(2026, 10, 21, 0, 0);
        when(messages.findByChatRoomIdOrderByTimestampAscIdAsc(2L)).thenReturn(List.of(
                message(1L, Message.Sender.USER, "질문", timestamp),
                message(2L, Message.Sender.BOT, "답변", timestamp),
                message(3L, Message.Sender.USER, "후속", timestamp),
                message(4L, Message.Sender.BOT, "후속 답변", timestamp)));
        when(evidence.load(1L, 2L)).thenReturn(List.of(new EvidenceItem("source-id", "LAW_CHUNK", "law:1",
                "민법", "https://www.law.go.kr/법령/민법", "고정 본문")));

        var result = service.getMessages(user, 2L);

        assertThat(result).extracting(value -> value.getSender()).containsExactly("USER", "BOT", "INFO", "USER", "BOT");
        assertThat(result.get(2).getContent()).contains("[[source-id]]", "고정 본문");
        assertThat(result.get(2).getTimestamp()).isEqualTo(timestamp);
        verify(messages, never()).save(any());
        verify(evidence).load(1L, 2L);
    }

    @Test
    void pagesRoomListAndUsesStableFirstMessagePreviewQuery() {
        PageRequest page = PageRequest.of(1, 10);
        when(rooms.findByUserOrderByCreatedAtDescIdDesc(user, page)).thenReturn(List.of(room));
        var preview = mock(MessageRepository.FirstMessagePreview.class);
        when(preview.getRoomId()).thenReturn(2L);
        when(preview.getContent()).thenReturn(TestEncryption.create().encrypt("첫 질문"));
        when(messages.findFirstMessagePreviews(List.of(2L))).thenReturn(List.of(preview));
        assertThat(service.getMyChatRooms(user, 1, 10)).hasSize(1)
                .first().extracting(value -> value.getPreview()).isEqualTo("첫 질문");
        verify(rooms).findByUserOrderByCreatedAtDescIdDesc(user, page);
        assertThatThrownBy(() -> service.getMyChatRooms(user, -1, 10)).isInstanceOf(MolelawException.class);
        assertThatThrownBy(() -> service.getMyChatRooms(user, 0, 101)).isInstanceOf(MolelawException.class);
    }

    @Test
    void invalidInitialQuestionStopsBeforeAnyModelOrPersistenceCall() throws Exception {
        var request = new ObjectMapper().readValue("{\"content\":\"" + "가".repeat(2001) + "\"}", CreateChatRoomRequest.class);
        assertThatThrownBy(() -> service.createRoomAndAsk(user, request)).isInstanceOf(MolelawException.class);
        verifyNoInteractions(extractor, answers, rooms, messages);
    }

    private Message message(Long id, Message.Sender sender, String content, LocalDateTime timestamp) {
        return Message.builder().id(id).chatRoom(room).sender(sender).content(TestEncryption.create().encrypt(content))
                .timestamp(timestamp).build();
    }
}
