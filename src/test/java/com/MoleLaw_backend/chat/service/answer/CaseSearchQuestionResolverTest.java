package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.chat.entity.Message;
import com.MoleLaw_backend.chat.repository.MessageRepository;
import com.MoleLaw_backend.chat.util.TestEncryption;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CaseSearchQuestionResolverTest {
    private final MessageRepository messages = mock(MessageRepository.class);
    private final com.MoleLaw_backend.chat.util.EncryptUtil encryptor = TestEncryption.create();
    private final CaseSearchQuestionResolver resolver = new CaseSearchQuestionResolver(messages, encryptor);
    private final String original = "게임 상에서의 욕설 협박에 관련해서 판례가 있다면 보고싶어";
    private final String consent = "네, 관련 판례를 검색해 주세요.";

    @Test void approvalRestoresOriginalRequestFromPersistedConsentTurn() {
        when(messages.findByChatRoomIdOrderByTimestampAscIdAsc(16L)).thenReturn(List.of(
                message(Message.Sender.USER, original, false),
                message(Message.Sender.BOT, "검색을 진행할까요?", true)));
        assertThat(resolver.resolve(16L, consent, true)).isEqualTo(original);
    }

    @Test void normalFollowUpDoesNotRestoreAnOldSearch() {
        assertThat(resolver.resolve(16L, "정리해주세요", false)).isEqualTo("정리해주세요");
        verifyNoInteractions(messages);
    }

    @Test void approvalWithoutPendingProposalKeepsCurrentQuestion() {
        for (var history : List.of(List.<Message>of(), List.of(
                message(Message.Sender.USER, original, false),
                message(Message.Sender.BOT, "완료된 답변", false)), List.of(
                message(Message.Sender.USER, original, false),
                message(Message.Sender.BOT, "검색할까요?", true),
                message(Message.Sender.USER, "취소", false),
                message(Message.Sender.BOT, "취소했습니다", false)))) {
            when(messages.findByChatRoomIdOrderByTimestampAscIdAsc(16L)).thenReturn(history);
            assertThat(resolver.resolve(16L, consent, true)).isEqualTo(consent);
        }
    }

    private Message message(Message.Sender sender, String text, boolean requested) {
        return Message.builder().sender(sender).content(encryptor.encrypt(text))
                .caseSearchRequested(requested).build();
    }
}
