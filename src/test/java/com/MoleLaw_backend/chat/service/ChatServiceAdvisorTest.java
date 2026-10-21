package com.MoleLaw_backend.chat.service;

import com.MoleLaw_backend.chat.dto.request.MessageRequest;
import com.MoleLaw_backend.chat.dto.response.GptAnswerResponse;
import com.MoleLaw_backend.chat.entity.ChatRoom;
import com.MoleLaw_backend.chat.entity.Message;
import com.MoleLaw_backend.chat.repository.ChatRoomRepository;
import com.MoleLaw_backend.chat.repository.MessageRepository;
import com.MoleLaw_backend.chat.util.EncryptUtil;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.law.service.ExtractKeyword;
import com.MoleLaw_backend.chat.service.answer.InitialAnswerService;
import com.MoleLaw_backend.user.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.MoleLaw_backend.chat.service.answer.FollowUpAnswerService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ChatServiceAdvisorTest {
    private final ChatRoomRepository rooms = mock(ChatRoomRepository.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final FollowUpAnswerService gpt = mock(FollowUpAnswerService.class);
    private final ChatTurnPersistenceService turns = mock(ChatTurnPersistenceService.class);
    private final ChatService service = new ChatService(rooms, messages, mock(InitialAnswerService.class), gpt, turns, mock(InitialChatPersistenceService.class),
            mock(ExtractKeyword.class));
    private final User user = User.builder().id(1L).build();
    private final ChatRoom room = ChatRoom.builder().id(2L).user(user).build();

    @Test
    void unauthorizedRoomDoesNotInvokeModelOrWriteMessages() throws Exception {
        when(rooms.findById(2L)).thenReturn(Optional.of(room));
        assertThatThrownBy(() -> service.askQuestion(User.builder().id(3L).build(), 2L, request()))
                .isInstanceOf(MolelawException.class);
        verifyNoInteractions(gpt, turns, messages);
    }

    @Test
    void failedModelDoesNotPersistCurrentQuestionOrAssistant() throws Exception {
        when(rooms.findById(2L)).thenReturn(Optional.of(room));
        when(gpt.generateAnswerWithContext(1L, 2L, "후속 질문")).thenThrow(new RuntimeException("provider"));
        assertThatThrownBy(() -> service.askQuestion(user, 2L, request())).isInstanceOf(MolelawException.class);
        verifyNoInteractions(turns, messages);
    }

    @Test
    void successfulAnswerPersistsOnePairAfterGeneration() throws Exception {
        when(rooms.findById(2L)).thenReturn(Optional.of(room));
        when(gpt.generateAnswerWithContext(1L, 2L, "후속 질문")).thenReturn(new GptAnswerResponse("답변", ""));
        when(turns.saveSuccessfulTurn(room, "후속 질문", "답변")).thenReturn(Message.builder()
                .id(4L).chatRoom(room).sender(Message.Sender.BOT).content(EncryptUtil.encrypt("답변")).build());
        service.askQuestion(user, 2L, request());
        var order = inOrder(gpt, turns);
        order.verify(gpt).generateAnswerWithContext(1L, 2L, "후속 질문");
        order.verify(turns).saveSuccessfulTurn(room, "후속 질문", "답변");
        verifyNoInteractions(messages);
    }

    private MessageRequest request() throws Exception {
        return new ObjectMapper().readValue("{\"content\":\"후속 질문\"}", MessageRequest.class);
    }
}
