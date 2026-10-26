package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.chat.service.evidence.CitationValidator;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.chat.service.evidence.ChatEvidenceService;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.law.service.retrieval.LegalEvidenceCollector;
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
import com.MoleLaw_backend.law.dto.retrieval.CollectedLegalEvidence;
import com.MoleLaw_backend.law.dto.retrieval.QuestionRetrievalPlan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegalAnswerInitialTest {
    private static final String ID = "00000000-0000-0000-0000-000000000001";
    private final LegalEvidenceCollector collector = mock(LegalEvidenceCollector.class);
    private final ChatModel model = mock(ChatModel.class);
    private final ChatClient followUp = mock(ChatClient.class);
    private final ChatEvidenceService roomEvidence = mock(ChatEvidenceService.class);
    private final LegalAnswerService service = new LegalAnswerService(collector, ChatClient.builder(model).build(),
            followUp, roomEvidence, new EvidencePromptBuilder(),
            new CitationValidator(), new EvidenceRenderer(), mock(FollowUpCaseToolsFactory.class), mock(FollowUpCasePlanner.class));
    private final EvidenceItem item = new EvidenceItem(ID, "LAW_CHUNK", "law-chunk:12", "민법 제548조",
            "https://www.law.go.kr/법령/민법", "원상회복 의무가 있다.");

    @BeforeEach
    void setUp() {
        when(collector.collectForAnswer("질문 {literal}")).thenReturn(new CollectedLegalEvidence(
                new QuestionRetrievalPlan(QuestionRetrievalPlan.Type.LAW_DISCOVERY,
                        QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE,
                        List.of("법령 검색"), List.of(), List.of("부지 위치")), List.of(item)));
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
        assertTrue(text.contains("GENERAL_GUIDANCE"));
        assertTrue(text.contains("부지 위치"));
        assertFalse(text.contains(item.sourceUrl()));
        assertEquals(List.of(item), result.evidence());
        assertTrue(result.response().getInfo().contains(java.net.URI.create(item.sourceUrl()).toASCIIString()));
        assertThrows(UnsupportedOperationException.class, () -> result.evidence().clear());
        verifyNoInteractions(followUp, roomEvidence);
    }

    @Test
    void partialAnswerIncludesUnresolvedIssuesEvenWhenModelOmitsThem() {
        var plan = new QuestionRetrievalPlan(QuestionRetrievalPlan.Type.CASE_ASSESSMENT,
                QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE, List.of("법령 검색"), List.of(), List.of());
        when(collector.collectForAnswer("질문 {literal}")).thenReturn(
                new CollectedLegalEvidence(plan, List.of(item), List.of("별표 적용 범위 미확인")));
        when(model.call(any(Prompt.class))).thenReturn(response("확인된 조문에 따른 일반 안내 [[" + ID + "]]"));
        var result = service.prepareInitialAnswer("질문 {literal}");
        assertTrue(result.response().getAnswer().contains("별표 적용 범위 미확인"));
        assertTrue(result.response().getAnswer().contains("확정할 수 없습니다"));
        assertEquals(List.of(item), result.evidence());
        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertTrue(prompt.getValue().getUserMessage().getText().contains("별표 적용 범위 미확인"));
    }

    @Test
    void streamsConfirmedInfoBeforeRealModelDeltasAndValidatesAtEnd() {
        var plan = new QuestionRetrievalPlan(QuestionRetrievalPlan.Type.LAW_EXPLANATION,
                QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE, List.of("검색"), List.of(), List.of());
        when(collector.collectForAnswer(eq("질문 {literal}"), any())).thenAnswer(call -> {
            com.MoleLaw_backend.law.dto.retrieval.RetrievalObserver observer = call.getArgument(1);
            observer.progress("INITIAL_SEARCH");
            observer.evidence(List.of(item));
            return new CollectedLegalEvidence(plan, List.of(item), List.of("미확인 쟁점"));
        });
        when(model.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.just(
                response("확인한 조문은 "), response("원상회복을 규정합니다 [[" + ID + "]]")));
        var updates = service.streamInitialAnswer("질문 {literal}").collectList().block(java.time.Duration.ofSeconds(5));
        assertNotNull(updates);
        assertEquals("progress", updates.get(0).type());
        assertEquals("info", updates.get(1).type());
        assertTrue(updates.get(1).text().contains(item.content()));
        assertFalse(updates.get(1).text().contains(ID));
        assertEquals(2, updates.stream().filter(u -> "answer_delta".equals(u.type())).count());
        assertTrue(updates.stream().filter(u -> "answer_delta".equals(u.type())).noneMatch(u -> u.text().contains(ID)));
        var completed = updates.get(updates.size() - 1);
        assertEquals("completed", completed.type());
        assertTrue(completed.prepared().response().getAnswer().contains("미확인 쟁점"));
        verify(model, never()).call(any(Prompt.class));
    }

    @Test
    void invalidStreamingCitationNeverProducesACompletedAnswer() {
        var plan = new QuestionRetrievalPlan(QuestionRetrievalPlan.Type.LAW_EXPLANATION,
                QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE, List.of("검색"), List.of(), List.of());
        when(collector.collectForAnswer(anyString(), any())).thenReturn(new CollectedLegalEvidence(plan, List.of(item)));
        when(model.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.just(response(
                "잘못된 인용 [[00000000-0000-0000-0000-000000000099]]")));
        var observed = new java.util.concurrent.CopyOnWriteArrayList<com.MoleLaw_backend.chat.dto.answer.InitialAnswerUpdate>();
        assertThrows(GptApiException.class, () -> service.streamInitialAnswer("질문 {literal}")
                .doOnNext(observed::add).blockLast(java.time.Duration.ofSeconds(5)));
        assertTrue(observed.stream().anyMatch(u -> "answer_delta".equals(u.type())));
        assertFalse(observed.stream().anyMatch(u -> u.prepared() != null));
    }

    @Test
    void rejectsInventedCitationsBeforeReturningAnswer() {
        when(model.call(any(Prompt.class))).thenReturn(response("답변 [[00000000-0000-0000-0000-000000000099]]"));
        assertThrows(GptApiException.class, () -> service.prepareInitialAnswer("질문 {literal}"));
    }

    @Test
    void retrievalFailurePreventsProviderCall() {
        when(collector.collectForAnswer("질문 {literal}")).thenThrow(new GptApiException(ErrorCode.GPT_API_FAILURE));
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
