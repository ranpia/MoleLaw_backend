package com.MoleLaw_backend.chat.service;

import com.MoleLaw_backend.chat.dto.request.MessageRequest;
import com.MoleLaw_backend.chat.dto.response.GptAnswerResponse;
import com.MoleLaw_backend.chat.entity.ChatRoom;
import com.MoleLaw_backend.chat.entity.Message;
import com.MoleLaw_backend.chat.repository.ChatRoomRepository;
import com.MoleLaw_backend.chat.repository.MessageRepository;
import com.MoleLaw_backend.chat.util.EncryptUtil;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.law.service.ExtractKeyword;
import com.MoleLaw_backend.chat.service.answer.LegalAnswerService;
import com.MoleLaw_backend.user.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ChatServiceAdvisorTest {
    private final ChatRoomRepository rooms = mock(ChatRoomRepository.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final LegalAnswerService gpt = mock(LegalAnswerService.class);
    private final ChatTurnService turns = mock(ChatTurnService.class);
    private final ChatService service = new ChatService(rooms, messages, gpt, turns,
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
    void unauthenticatedHistoryReadStopsBeforeRoomOrMessageLookup() {
        assertThatThrownBy(() -> service.getMessages(null, 2L))
                .isInstanceOfSatisfying(MolelawException.class,
                        failure -> org.assertj.core.api.Assertions.assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.USER_NOT_AUTHENTICATED));
        verifyNoInteractions(rooms, messages, gpt, turns);
    }

    @Test
    void unauthenticatedDeleteStopsBeforeAnyRepositoryAccess() {
        assertThatThrownBy(() -> service.deleteChatRoom(null, 2L))
                .isInstanceOfSatisfying(MolelawException.class,
                        failure -> org.assertj.core.api.Assertions.assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.USER_NOT_AUTHENTICATED));
        verifyNoInteractions(rooms, messages, gpt, turns);
    }

    @Test
    void foreignRoomCannotBeDeleted() {
        when(rooms.findById(2L)).thenReturn(Optional.of(room));
        assertThatThrownBy(() -> service.deleteChatRoom(User.builder().id(3L).build(), 2L))
                .isInstanceOfSatisfying(MolelawException.class,
                        failure -> org.assertj.core.api.Assertions.assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.UNAUTHORIZED_CHATROOM_ACCESS));
        verify(rooms).findById(2L);
        verifyNoMoreInteractions(rooms);
        verifyNoInteractions(messages, gpt, turns);
    }

    @Test
    void failedModelDoesNotPersistCurrentQuestionOrAssistant() throws Exception {
        when(rooms.findById(2L)).thenReturn(Optional.of(room));
        when(gpt.answerFollowUp(1L, 2L, "후속 질문")).thenThrow(new RuntimeException("provider"));
        assertThatThrownBy(() -> service.askQuestion(user, 2L, request())).isInstanceOf(MolelawException.class);
        verifyNoInteractions(turns, messages);
    }

    @Test
    void successfulAnswerPersistsOnePairAfterGeneration() throws Exception {
        when(rooms.findById(2L)).thenReturn(Optional.of(room));
        when(gpt.answerFollowUp(1L, 2L, "후속 질문")).thenReturn(new GptAnswerResponse("답변", ""));
        when(turns.saveFollowUpTurn(room, "후속 질문", "답변")).thenReturn(Message.builder()
                .id(4L).chatRoom(room).sender(Message.Sender.BOT).content(EncryptUtil.encrypt("답변")).build());
        service.askQuestion(user, 2L, request());
        var order = inOrder(gpt, turns);
        order.verify(gpt).answerFollowUp(1L, 2L, "후속 질문");
        order.verify(turns).saveFollowUpTurn(room, "후속 질문", "답변");
        verifyNoInteractions(messages);
    }

    private MessageRequest request() throws Exception {
        return new ObjectMapper().readValue("{\"content\":\"후속 질문\"}", MessageRequest.class);
    }
}
