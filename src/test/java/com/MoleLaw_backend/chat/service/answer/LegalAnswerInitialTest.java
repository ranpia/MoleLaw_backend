package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.chat.service.evidence.CitationValidator;
import com.MoleLaw_backend.chat.service.evidence.EvidenceItem;
import com.MoleLaw_backend.chat.service.evidence.ChatEvidenceService;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.law.service.LegalEvidenceCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LegalAnswerInitialTest {
    private static final String ID = "00000000-0000-0000-0000-000000000001";
    private final LegalEvidenceCollector collector = mock(LegalEvidenceCollector.class);
    private final ChatModel model = mock(ChatModel.class);
    private final ChatClient followUp = mock(ChatClient.class);
    private final ChatEvidenceService roomEvidence = mock(ChatEvidenceService.class);
    private final LegalAnswerService service = new LegalAnswerService(collector, ChatClient.builder(model).build(),
            followUp, roomEvidence, new EvidencePromptBuilder(),
            new CitationValidator(), new EvidenceRenderer());
    private final EvidenceItem item = new EvidenceItem(ID, "LAW_CHUNK", "law-chunk:12", "민법 제548조",
            "https://www.law.go.kr/법령/민법", "원상회복 의무가 있다.");

    @BeforeEach
    void setUp() {
        when(collector.collect("질문 {literal}")).thenReturn(List.of(item));
    }

    @Test
    void generatesWithExactSnapshotAndReturnsValidatedCitationAndServerRenderedSources() {
        when(model.call(any(Prompt.class))).thenReturn(response("답변 [[" + ID + "]]"));
        var result = service.prepareInitialAnswer("질문 {literal}");
        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        String text = prompt.getValue().getUserMessage().getText();
        assertTrue(text.contains("질문 {literal}"));
        assertTrue(text.contains("[[" + ID + "]]"));
        assertTrue(text.contains(item.content()));
        assertFalse(text.contains(item.sourceUrl()));
        assertEquals(List.of(item), result.evidence());
        assertTrue(result.response().getInfo().contains(java.net.URI.create(item.sourceUrl()).toASCIIString()));
        assertThrows(UnsupportedOperationException.class, () -> result.evidence().clear());
        verifyNoInteractions(followUp, roomEvidence);
    }

    @Test
    void rejectsInventedCitationsBeforeReturningAnswer() {
        when(model.call(any(Prompt.class))).thenReturn(response("답변 [[00000000-0000-0000-0000-000000000099]]"));
        assertThrows(GptApiException.class, () -> service.prepareInitialAnswer("질문 {literal}"));
    }

    @Test
    void retrievalFailurePreventsProviderCall() {
        when(collector.collect("질문 {literal}")).thenThrow(new GptApiException(ErrorCode.GPT_API_FAILURE));
        assertThrows(GptApiException.class, () -> service.prepareInitialAnswer("질문 {literal}"));
        verify(model, never()).call(any(Prompt.class));
    }

    @Test
    void providerFailureIsMappedWithoutLeakingProviderDetails() {
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("secret provider response"));
        var failure = assertThrows(GptApiException.class, () -> service.prepareInitialAnswer("질문 {literal}"));
        assertEquals(ErrorCode.GPT_API_FAILURE, failure.getErrorCode());
        assertFalse(failure.getMessage().contains("secret provider response"));
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
