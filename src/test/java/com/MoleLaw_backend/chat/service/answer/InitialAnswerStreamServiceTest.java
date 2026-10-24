package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.chat.dto.answer.InitialAnswerUpdate;
import com.MoleLaw_backend.chat.dto.answer.PreparedLegalAnswer;
import com.MoleLaw_backend.chat.dto.response.ChatRoomCreationResponse;
import com.MoleLaw_backend.chat.dto.response.LegalAnswerResponse;
import com.MoleLaw_backend.chat.service.ChatService;
import com.MoleLaw_backend.user.entity.User;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InitialAnswerStreamServiceTest {
    private final LegalAnswerService answers = mock(LegalAnswerService.class);
    private final ChatService chats = mock(ChatService.class);
    private final InitialAnswerStreamService service = new InitialAnswerStreamService(answers, chats, 1, Duration.ofSeconds(5));

    @Test void savesOnlyValidatedCompletionAndPublishesRoomAfterCommit() {
        User owner = mock(User.class);
        var prepared = new PreparedLegalAnswer(new LegalAnswerResponse("확정 답변", "근거"), List.of());
        var room = ChatRoomCreationResponse.builder().id(42L).messages(List.of()).build();
        when(answers.streamInitialAnswer("질문")).thenReturn(Flux.just(InitialAnswerUpdate.progress("INITIAL_SEARCH"),
                InitialAnswerUpdate.info("근거"), InitialAnswerUpdate.delta("임시"), InitialAnswerUpdate.complete(prepared)));
        when(chats.saveStreamedInitialAnswer(owner, "질문", prepared)).thenReturn(room);
        var events = service.stream("질문", owner).collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(e -> e.event()).containsExactly("progress", "info", "answer_delta", "progress", "completed");
        assertThat(events.get(events.size() - 1).data().room().getId()).isEqualTo(42L);
        verify(chats).saveStreamedInitialAnswer(owner, "질문", prepared);
    }

    @Test void providerFailureDiscardsProvisionalAnswerAndSanitizesError() {
        when(answers.streamInitialAnswer("질문")).thenReturn(Flux.concat(Flux.just(InitialAnswerUpdate.delta("임시")),
                Flux.error(new IllegalStateException("private provider details"))));
        var events = service.stream("질문", mock(User.class)).collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(e -> e.event()).containsExactly("answer_delta", "error");
        assertThat(events.get(1).data().code()).isEqualTo("GPT_API_FAILURE");
        assertThat(events.get(1).data().message()).doesNotContain("private");
        verifyNoInteractions(chats);
    }

    @Test void disconnectedStreamCancelsWorkAndReleasesAdmissionSlot() {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        when(answers.streamInitialAnswer("질문")).thenReturn(Flux.concat(Flux.just(InitialAnswerUpdate.info("근거")),
                Flux.<InitialAnswerUpdate>never()).doOnCancel(() -> cancelled.set(true)));
        service.stream("질문", mock(User.class)).take(1).blockLast(Duration.ofSeconds(5));
        assertThat(cancelled).isTrue();
        var next = service.stream("질문", null).take(1).blockFirst(Duration.ofSeconds(5));
        assertThat(next.event()).isEqualTo("info");
        verifyNoInteractions(chats);
    }
}
