package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.chat.config.AnswerChatClientConfig;
import com.MoleLaw_backend.chat.entity.ChatRoom;
import com.MoleLaw_backend.chat.entity.Message;
import com.MoleLaw_backend.chat.repository.ChatRoomRepository;
import com.MoleLaw_backend.chat.repository.MessageRepository;
import com.MoleLaw_backend.chat.service.memory.PersistedChatMemory;
import com.MoleLaw_backend.chat.service.evidence.ChatEvidenceService;
import com.MoleLaw_backend.chat.service.evidence.CitationValidator;
import com.MoleLaw_backend.chat.service.evidence.EvidenceItem;
import com.MoleLaw_backend.chat.util.EncryptUtil;
import com.MoleLaw_backend.common.ai.config.AiConfig;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.user.entity.User;
import com.MoleLaw_backend.law.service.LegalEvidenceCollector;
import org.springframework.ai.chat.client.ChatClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LegalAnswerFollowUpTest {
    private static final String EVIDENCE_ID = "00000000-0000-0000-0000-000000000001";
    private static final String CITED_ANSWER = "new answer [[" + EVIDENCE_ID + "]]";
    private final MessageRepository messages = mock(MessageRepository.class);
    private final ChatRoomRepository rooms = mock(ChatRoomRepository.class);
    private final ChatModel model = mock(ChatModel.class);
    private final ChatEvidenceService evidence = mock(ChatEvidenceService.class);
    private final LegalEvidenceCollector collector = mock(LegalEvidenceCollector.class);
    private LegalAnswerService service;

    @BeforeEach
    void setUp() {
        var memory = new PersistedChatMemory(messages, rooms, 20);
        var builder = new AiConfig().baseAiChatClientBuilder(model);
        var config = new AnswerChatClientConfig();
        service = new LegalAnswerService(collector, mock(ChatClient.class),
                config.followUpAnswerChatClient(config.legalAnswerChatClient(builder, 0.3, 4096,
                        mock(com.MoleLaw_backend.common.ai.AiPrompts.class)), memory), evidence,
                new EvidencePromptBuilder(), new CitationValidator(), new EvidenceRenderer());
        when(evidence.load(1L, 2L)).thenReturn(List.of(new EvidenceItem(EVIDENCE_ID,
                "LAW_CHUNK", "law-1", "Civil law article", "https://www.law.go.kr/law/1", "original statute text")));
    }

    @Test
    void actualAdvisorLoadsDecryptedRolesAndAddsCurrentQuestionOnce() {
        ownRoom();
        when(messages.findByChatRoomIdOrderByTimestampAsc(2L)).thenReturn(List.of(
                message(2L, Message.Sender.BOT, "previous answer"),
                message(1L, Message.Sender.USER, "first question"),
                message(3L, Message.Sender.INFO, "private metadata")));
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(CITED_ANSWER)))));

        assertThat(service.answerFollowUp(1L, 2L, "current question").getAnswer())
                .isEqualTo(CITED_ANSWER);
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions()).extracting(org.springframework.ai.chat.messages.Message::getMessageType)
                .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT, MessageType.USER);
        var texts = prompt.getValue().getInstructions().stream()
                .map(org.springframework.ai.chat.messages.Message::getText).toList();
        assertThat(texts.get(1)).isEqualTo("first question");
        assertThat(texts.get(2)).isEqualTo("previous answer");
        assertThat(texts.get(3)).contains("current question", EVIDENCE_ID, "original statute text");
        assertThat(texts).doesNotContain("private metadata");
        verify(messages, never()).save(any());
        verify(messages, never()).deleteAll();
        verifyNoInteractions(collector);
    }

    @Test
    void crossRoomHistoryNeverReachesModel() {
        when(evidence.load(1L, 2L)).thenThrow(new MolelawException(ErrorCode.UNAUTHORIZED_CHATROOM_ACCESS));
        when(rooms.findById(2L)).thenReturn(Optional.of(ChatRoom.builder()
                .id(2L).user(User.builder().id(9L).build()).build()));
        assertThatThrownBy(() -> service.answerFollowUp(1L, 2L, "question"))
                .isInstanceOf(MolelawException.class);
        verifyNoInteractions(messages);
        verify(model, never()).call(any(Prompt.class));
    }

    @Test
    void unknownCitationRejectsAnswerWithoutPersisting() {
        assertRejectedAnswer("answer [[00000000-0000-0000-0000-000000000099]]");
    }

    @Test
    void uncitedAnswerRejectsAnswerWithoutPersisting() {
        assertRejectedAnswer("answer without source citation");
    }

    @Test
    void missingSnapshotStopsBeforeHistoryAndProvider() {
        when(evidence.load(1L, 2L)).thenThrow(new MolelawException(ErrorCode.GPT_API_FAILURE));
        assertThatThrownBy(() -> service.answerFollowUp(1L, 2L, "question"))
                .isInstanceOf(MolelawException.class);
        verifyNoInteractions(messages);
        verify(model, never()).call(any(Prompt.class));
    }

    private void assertRejectedAnswer(String answer) {
        ownRoom();
        when(messages.findByChatRoomIdOrderByTimestampAsc(2L)).thenReturn(List.of());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(answer)))));
        assertThatThrownBy(() -> service.answerFollowUp(1L, 2L, "question"))
                .isInstanceOf(GptApiException.class);
        verify(messages, never()).save(any());
        verify(evidence, never()).freeze(any(), any());
    }

    @Test
    void modelFailureDoesNotAutomaticallyPersistPromptOrResponse() {
        ownRoom();
        when(messages.findByChatRoomIdOrderByTimestampAsc(2L)).thenReturn(List.of());
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("provider failure"));
        assertThatThrownBy(() -> service.answerFollowUp(1L, 2L, "question"))
                .isInstanceOf(GptApiException.class);
        verify(messages).findByChatRoomIdOrderByTimestampAsc(2L);
        verifyNoMoreInteractions(messages);
    }

    private void ownRoom() {
        when(rooms.findById(2L)).thenReturn(Optional.of(ChatRoom.builder()
                .id(2L).user(User.builder().id(1L).build()).build()));
    }

    private Message message(long id, Message.Sender sender, String content) {
        return Message.builder().id(id).sender(sender).content(EncryptUtil.encrypt(content))
                .timestamp(LocalDateTime.of(2026, 10, 21, 12, 0)).build();
    }
}
