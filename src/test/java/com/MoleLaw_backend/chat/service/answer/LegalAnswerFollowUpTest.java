package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.chat.util.TestEncryption;

import com.MoleLaw_backend.chat.config.AnswerChatClientConfig;
import com.MoleLaw_backend.chat.entity.ChatRoom;
import com.MoleLaw_backend.chat.entity.Message;
import com.MoleLaw_backend.chat.repository.ChatRoomRepository;
import com.MoleLaw_backend.chat.repository.MessageRepository;
import com.MoleLaw_backend.chat.service.memory.PersistedChatMemory;
import com.MoleLaw_backend.chat.service.evidence.ChatEvidenceService;
import com.MoleLaw_backend.chat.service.evidence.CitationValidator;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.chat.util.EncryptUtil;
import com.MoleLaw_backend.common.ai.config.AiConfig;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.user.entity.User;
import com.MoleLaw_backend.law.service.retrieval.LegalEvidenceCollector;
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
    @Test void preparationDraftAndMachineCodesNeverAppearInAnswerDeltasAndFinalWriterHasNoTools() {
        ownRoom();
        when(messages.findByChatRoomIdOrderByTimestampAscIdAsc(2L)).thenReturn(List.of());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("중간 검토 초안은 EMPTY")))));
        when(model.stream(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            assertThat(((org.springframework.ai.model.tool.ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks()).isEmpty();
            return reactor.core.publisher.Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("조회는 EMP")))),
                    new ChatResponse(List.of(new Generation(new AssistantMessage("TY입니다 [[" + EVIDENCE_ID + "]]")))));
        });
        var updates = service.streamFollowUp(1L, 2L, "설명해 주세요", false).collectList().block(java.time.Duration.ofSeconds(5));
        String shown = updates.stream().filter(u -> "answer_delta".equals(u.type())).map(u -> u.text()).collect(java.util.stream.Collectors.joining());
        assertThat(shown).doesNotContain("중간 검토 초안", "EMPTY", "EMP").contains("후보를 찾지 못한 상태");
        assertThat(updates.get(updates.size() - 1).followUp().response().getAnswer()).doesNotContain("EMPTY", "중간 검토 초안");
    }
    @Test void newlyReadRankedCaseUpdatesInfoWithoutRawSourceJsonBeforeCompletion() {
        ownRoom();
        when(messages.findByChatRoomIdOrderByTimestampAscIdAsc(2L)).thenReturn(List.of());
        String caseId = "00000000-0000-0000-0000-000000000099";
        var item = new EvidenceItem(caseId, "CASE_BODY", "101", "임금", "https://www.law.go.kr/precInfoP.do?precSeq=101",
                "{\"판결요지\":\"휴가 사용 촉진에 관한 판단\",\"판례내용\":\"검증용 판결 본문\"}");
        var candidate = new com.MoleLaw_backend.law.dto.tool.LegalToolContracts.Candidate("101", null, "임금", null, "사건", "20260101", "대법원");
        when(casePipeline.search(any(), any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn(new com.MoleLaw_backend.law.dto.retrieval.CaseSearchOutcome("AVAILABLE", List.of(candidate), java.util.Map.of("101", item), List.of(), 0, 1, 1, false, false, false));
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            var callbacks = ((org.springframework.ai.model.tool.ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks();
            callbacks.stream().filter(c -> c.getToolDefinition().name().equals("searchRoomCases")).findFirst().orElseThrow()
                    .call("{\"lawEvidenceId\":\"" + EVIDENCE_ID + "\",\"query\":\"사용 촉진\",\"page\":1}");
            callbacks.stream().filter(c -> c.getToolDefinition().name().equals("readRoomCase")).findFirst().orElseThrow().call("{\"caseId\":\"101\"}");
            return new ChatResponse(List.of(new Generation(new AssistantMessage("준비 중간 내용은 보이면 안 됨"))));
        });
        when(model.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("판례에 따른 설명 [[" + caseId + "]]"))))));
        var updates = service.streamFollowUp(1L, 2L, "관련 판례를 검색해 주세요", true).collectList().block(java.time.Duration.ofSeconds(5));
        var infos = updates.stream().filter(u -> "info".equals(u.type())).map(u -> u.text()).toList();
        assertThat(infos).anyMatch(info -> info.contains("휴가 사용 촉진에 관한 판단") && info.contains("precSeq=101"));
        assertThat(infos).allMatch(info -> !info.contains("\"판례내용\"") && !info.contains(caseId));
        assertThat(updates.get(updates.size() - 1).followUp().newEvidence()).containsExactly(item);
    }

    @Test
    void streamsFollowUpUsingPersistedAdvisorAndHidesSplitCitationUntilValidatedCompletion() {
        ownRoom();
        when(messages.findByChatRoomIdOrderByTimestampAscIdAsc(2L)).thenReturn(List.of());
        when(model.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("확인한 근거 [")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("[" + EVIDENCE_ID.substring(0, 14))))),
                new ChatResponse(List.of(new Generation(new AssistantMessage(EVIDENCE_ID.substring(14) + "]]"))))));
        var updates = service.streamFollowUp(1L, 2L, "후속 질문", false).collectList().block(java.time.Duration.ofSeconds(5));
        String shown = updates.stream().filter(u -> "answer_delta".equals(u.type())).map(u -> u.text())
                .collect(java.util.stream.Collectors.joining());
        assertThat(shown).isEqualTo("확인한 근거 [근거 1]").doesNotContain(EVIDENCE_ID, "[[");
        assertThat(updates.stream().filter(u -> "info".equals(u.type())).map(u -> u.text()).toList())
                .allMatch(info -> !info.contains(EVIDENCE_ID));
        assertThat(updates.get(updates.size() - 1).followUp().response().getAnswer())
                .isEqualTo("확인한 근거 [[" + EVIDENCE_ID + "]]");
        verify(model).call(any(Prompt.class));
        verifyNoInteractions(collector);
    }

    @Test
    void streamingConsentUsesServerGroundedQuestionAndDoesNotGrantSearchConsent() {
        ownRoom();
        when(messages.findByChatRoomIdOrderByTimestampAscIdAsc(2L)).thenReturn(List.of());
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            var callbacks = ((org.springframework.ai.model.tool.ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks();
            assertThat(callbacks).noneMatch(callback -> callback.getToolDefinition().name().equals("searchRoomCases"));
            callbacks.stream().filter(callback -> callback.getToolDefinition().name().equals("requestCaseSearchConsent"))
                    .findFirst().orElseThrow().call("{\"lawEvidenceId\":\"" + EVIDENCE_ID + "\",\"reason\":\"판례 보강 필요\"}");
            return new ChatResponse(List.of(new Generation(new AssistantMessage("이 중간 답변은 표시하지 않습니다"))));
        });
        var result = service.streamFollowUp(1L, 2L, "판례도 필요한가요?", false).blockLast(java.time.Duration.ofSeconds(5)).followUp();
        assertThat(result.caseSearchRequested()).isTrue();
        assertThat(result.caseSearchConsent()).isFalse();
        assertThat(result.newEvidence()).isEmpty();
        assertThat(result.response().getAnswer()).contains("[[" + EVIDENCE_ID + "]]", "동의하신 경우에만");
    }

    private static final String EVIDENCE_ID = "00000000-0000-0000-0000-000000000001";
    private static final String CITED_ANSWER = "new answer [[" + EVIDENCE_ID + "]]";
    private final MessageRepository messages = mock(MessageRepository.class);
    private final ChatRoomRepository rooms = mock(ChatRoomRepository.class);
    private final ChatModel model = mock(ChatModel.class);
    private final ChatEvidenceService evidence = mock(ChatEvidenceService.class);
    private final LegalEvidenceCollector collector = mock(LegalEvidenceCollector.class);
    private final com.MoleLaw_backend.law.service.retrieval.CaseSearchPipeline casePipeline = mock(com.MoleLaw_backend.law.service.retrieval.CaseSearchPipeline.class);
    private LegalAnswerService service;

    @BeforeEach
    void setUp() {
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("준비 완료")))));
        var memory = new PersistedChatMemory(messages, rooms, 20, TestEncryption.create());
        var builder = new AiConfig().baseAiChatClientBuilder(model);
        var config = new AnswerChatClientConfig();
        service = new LegalAnswerService(collector, mock(ChatClient.class),
                config.followUpAnswerChatClient(config.legalAnswerChatClient(builder, "gpt-5.4-mini", "medium", 16384,
                        mock(com.MoleLaw_backend.common.ai.AiPrompts.class)), memory, "gpt-5.4-mini", 16384), evidence,
                new EvidencePromptBuilder(), new CitationValidator(), new EvidenceRenderer(), new FollowUpCaseToolsFactory(
                        mock(com.MoleLaw_backend.law.service.tool.LegalApiToolService.class),
                        mock(com.MoleLaw_backend.law.service.tool.CaseEvidenceReader.class),
                        casePipeline));
        when(evidence.load(1L, 2L)).thenReturn(List.of(new EvidenceItem(EVIDENCE_ID,
                "LAW_CHUNK", "law-1", "Civil law article", "https://www.law.go.kr/law/1", "original statute text")));
    }

    @Test
    void consentRequestUsesGroundedServerQuestionWithoutRequiringAnUncitedModelAnswer() {
        ownRoom();
        when(messages.findByChatRoomIdOrderByTimestampAscIdAsc(2L)).thenReturn(List.of());
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            var callbacks = ((org.springframework.ai.model.tool.ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks();
            assertThat(callbacks).noneMatch(callback -> callback.getToolDefinition().name().equals("searchRoomCases"));
            callbacks.stream().filter(callback -> callback.getToolDefinition().name().equals("requestCaseSearchConsent"))
                    .findFirst().orElseThrow().call("{\"lawEvidenceId\":\"" + EVIDENCE_ID + "\",\"reason\":\"판례 보강 필요\"}");
            return new ChatResponse(List.of(new Generation(new AssistantMessage("찾아볼까요?"))));
        });
        var result = service.prepareFollowUp(1L, 2L, "판례도 필요한가요?");
        assertThat(result.caseSearchRequested()).isTrue();
        assertThat(result.caseSearchConsent()).isFalse();
        assertThat(result.response().getAnswer()).contains("[[" + EVIDENCE_ID + "]]", "동의하신 경우에만");
        assertThat(result.newEvidence()).isEmpty();
        verifyNoInteractions(collector);
        verify(messages, never()).save(any());
    }

    @Test
    void actualAdvisorLoadsDecryptedRolesAndAddsCurrentQuestionOnce() {
        ownRoom();
        when(messages.findByChatRoomIdOrderByTimestampAscIdAsc(2L)).thenReturn(List.of(
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
        when(messages.findByChatRoomIdOrderByTimestampAscIdAsc(2L)).thenReturn(List.of());
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
        when(messages.findByChatRoomIdOrderByTimestampAscIdAsc(2L)).thenReturn(List.of());
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("provider failure"));
        assertThatThrownBy(() -> service.answerFollowUp(1L, 2L, "question"))
                .isInstanceOf(GptApiException.class);
        verify(messages).findByChatRoomIdOrderByTimestampAscIdAsc(2L);
        verifyNoMoreInteractions(messages);
    }

    private void ownRoom() {
        when(rooms.findById(2L)).thenReturn(Optional.of(ChatRoom.builder()
                .id(2L).user(User.builder().id(1L).build()).build()));
    }

    private Message message(long id, Message.Sender sender, String content) {
        return Message.builder().id(id).sender(sender).content(TestEncryption.create().encrypt(content))
                .timestamp(LocalDateTime.of(2026, 10, 21, 12, 0)).build();
    }
}
