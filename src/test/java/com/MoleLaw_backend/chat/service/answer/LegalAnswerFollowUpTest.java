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
    @Test void ordinaryQuestionSkipsCasePreparationAndGoesStraightToFinalWriter() {
        ownRoom();
        var law = new EvidenceItem(EVIDENCE_ID, "LAW_CHUNK", "law-1", "법령", "https://www.law.go.kr", "본문");
        var oldCase = new EvidenceItem("00000000-0000-0000-0000-000000000099", "CASE_BODY", "242151",
                "과거 검색 판결", "https://www.law.go.kr", "과거 판결 본문");
        when(evidence.load(1L, 2L)).thenReturn(List.of(law, oldCase));
        when(model.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage(CITED_ANSWER))))));
        var updates = service.streamFollowUp(1L, 2L, "협박죄 성립 요건을 설명해줘", false).collectList().block();
        assertThat(updates.get(updates.size() - 1).followUp().response().getAnswer()).isEqualTo(CITED_ANSWER);
        assertThat(updates).noneSatisfy(update -> {
            assertThat(update.type()).isEqualTo("progress");
            assertThat(update.text()).isEqualTo("CASE_BODY_READY");
        });
        verify(model, never()).call(any(Prompt.class));
        verify(casePipeline, never()).search(any(), any(), anyString(), anyInt(), any(), any());
    }

    @Test void explicitExistingCaseQuestionReadsOnlyTheRequestedRoomCase() {
        ownRoom();
        var existing = new EvidenceItem("00000000-0000-0000-0000-000000000099", "CASE_BODY", "242151",
                "기존 판결", "https://www.law.go.kr", "확인한 판결 본문");
        var law = new EvidenceItem(EVIDENCE_ID, "LAW_CHUNK", "law-1", "법령", "https://www.law.go.kr", "법령 본문");
        when(evidence.load(1L, 2L)).thenReturn(List.of(law, existing));
        when(routing.plan(anyString(), any(), anyString())).thenReturn(new com.MoleLaw_backend.chat.dto.answer.FollowUpCasePlan(
                com.MoleLaw_backend.chat.dto.answer.FollowUpCasePlan.Action.READ_ROOM_CASES, null, "", "기존 판결의 판단 이유 요청"));
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            var callbacks = ((org.springframework.ai.model.tool.ToolCallingChatOptions) ((Prompt) call.getArgument(0)).getOptions()).getToolCallbacks();
            assertThat(callbacks).extracting(c -> c.getToolDefinition().name()).containsExactly("readRoomCase");
            callbacks.get(0).call("{\"caseId\":\"242151\"}");
            return new ChatResponse(List.of(new Generation(new AssistantMessage("준비 완료"))));
        });
        when(model.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("판결의 판단 이유입니다 [[" + existing.id() + "]]"))))));
        var result = service.streamFollowUp(1L, 2L, "그 판결의 판단 이유를 설명해줘", false).blockLast().followUp();
        assertThat(result.newEvidence()).isEmpty();
        assertThat(result.response().getAnswer()).contains(existing.id());
        verify(model).call(any(Prompt.class));
        verify(casePipeline, never()).search(any(), any(), anyString(), anyInt(), any(), any());
    }

    @Test void invalidStreamCitationIsRepairedOnceUsingTheSameEvidenceWithoutSearchTools() {
        ownRoom();
        when(model.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("출처가 빠진 초안"))))));
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            assertThat(prompt.getSystemMessage().getText()).contains("출처 표기를 교정");
            assertThat(prompt.getUserMessage().getText()).contains("출처가 빠진 초안", EVIDENCE_ID, "original statute text");
            assertThat(prompt.getInstructions()).hasSize(2);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(CITED_ANSWER))));
        });
        var updates = service.streamFollowUp(1L, 2L, "설명해줘", false).collectList().block();
        assertThat(updates).anySatisfy(update -> {
            assertThat(update.type()).isEqualTo("progress");
            assertThat(update.text()).isEqualTo("ANSWER_REPAIR");
        });
        assertThat(updates.get(updates.size() - 1).followUp().response().getAnswer()).isEqualTo(CITED_ANSWER);
        verify(model).call(any(Prompt.class));
        verify(casePipeline, never()).search(any(), any(), anyString(), anyInt(), any(), any());
    }

    @Test void aSecondInvalidCitationStillFailsWithoutFurtherRetries() {
        ownRoom();
        when(model.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("인용 없는 초안"))))));
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("여전히 인용 없음")))));
        assertThatThrownBy(() -> service.streamFollowUp(1L, 2L, "설명해줘", false).blockLast()).isInstanceOf(GptApiException.class);
        verify(model).call(any(Prompt.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 40})
    void allSelectedContextsReachSearchAndEmptySelectionNeverUsesRoomLaw(int count) {
        ownRoom();
        var selected = java.util.stream.IntStream.rangeClosed(1, count).mapToObj(index -> new EvidenceItem(
                "00000000-0000-0000-0000-" + String.format("%012d", index), "LAW_CHUNK", "law-" + index,
                "선택 법령 " + index, "https://www.law.go.kr", "본문")).toList();
        when(evidence.load(1L, 2L)).thenReturn(selected.isEmpty() ? List.of(new EvidenceItem(
                EVIDENCE_ID, "LAW_CHUNK", "unused-law", "선택하지 않은 방 법령", "https://www.law.go.kr", "본문")) : selected);
        routeSearch();
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(CITED_ANSWER)))));
        when(model.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage(CITED_ANSWER))))));
        when(casePipeline.search(any(), any(), anyString(), anyInt(), any(), any())).thenAnswer(call -> {
            Object state = call.getArgument(4);
            assertThat(org.springframework.test.util.ReflectionTestUtils.getField(state, "lawContexts")).isEqualTo(selected);
            assertThat((EvidenceItem) call.getArgument(1)).isEqualTo(selected.isEmpty() ? null : selected.get(0));
            return null;
        });
        var ids = selected.stream().map(EvidenceItem::id).toList();
        service.prepareFollowUp(1L, 2L, "검색해줘", true, ids);
        service.streamFollowUp(1L, 2L, "검색해줘", true, ids).blockLast();
        verify(casePipeline, times(2)).search(any(), any(), anyString(), anyInt(), any(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void userSelectedLawOverridesAutomaticAnchorInSyncAndStreaming(boolean streaming) {
        ownRoom();
        String selectedId = "00000000-0000-0000-0000-000000000002";
        var automatic = new EvidenceItem(EVIDENCE_ID, "LAW_CHUNK", "law-1", "자동 법령", "https://www.law.go.kr", "본문");
        var selected = new EvidenceItem(selectedId, "LAW_CHUNK", "law-2", "선택한 법령", "https://www.law.go.kr", "본문");
        when(evidence.load(1L, 2L)).thenReturn(List.of(automatic, selected));
        when(messages.findByChatRoomIdOrderByTimestampAscIdAsc(2L)).thenReturn(List.of());
        routeSearch();
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(CITED_ANSWER)))));
        when(model.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage(CITED_ANSWER))))));

        if (streaming) service.streamFollowUp(1L, 2L, "게임 욕설 협박 판례", true, java.util.List.of(selectedId)).blockLast();
        else service.prepareFollowUp(1L, 2L, "게임 욕설 협박 판례", true, java.util.List.of(selectedId));

        verify(casePipeline).search(any(), eq(selected), eq("판례 검색"), eq(1), any(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void invalidLawSelectionStopsBeforePlanningOrExternalSearch(boolean streaming) {
        var caseBody = new EvidenceItem("case-id", "CASE_BODY", "123", "판례", "https://www.law.go.kr", "본문");
        when(evidence.load(1L, 2L)).thenReturn(List.of(caseBody));
        for (String selectedId : List.of("case-id", "another-room-id")) {
            assertThatThrownBy(() -> {
                if (streaming) service.streamFollowUp(1L, 2L, "질문", true, java.util.List.of(selectedId)).blockLast();
                else service.prepareFollowUp(1L, 2L, "질문", true, java.util.List.of(selectedId));
            }).isInstanceOfSatisfying(MolelawException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        }
        assertThatThrownBy(() -> service.prepareFollowUp(1L, 2L, "질문", false, java.util.List.of(EVIDENCE_ID)))
                .isInstanceOf(MolelawException.class);
        verifyNoInteractions(routing);
        verify(casePipeline, never()).search(any(), any(), anyString(), anyInt(), any(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void consentMessageDoesNotReplaceOriginalSearchQuestion(boolean streaming) {
        ownRoom();
        String original = "게임 상에서의 욕설 협박에 관련해서 판례가 있다면 보고싶어";
        var proposal = Message.builder().sender(Message.Sender.BOT)
                .content(TestEncryption.create().encrypt("검색을 진행할까요?"))
                .caseSearchRequested(true).build();
        when(messages.findByChatRoomIdOrderByTimestampAscIdAsc(2L)).thenReturn(List.of(
                message(1L, Message.Sender.USER, original), proposal));
        routeSearch();
        when(casePipeline.search(any(), any(), anyString(), anyInt(), any(), any())).thenAnswer(call -> {
            Object searchState = call.getArgument(4);
            assertThat(org.springframework.test.util.ReflectionTestUtils.getField(searchState, "userQuestion"))
                    .isEqualTo(original);
            return null;
        });
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage(CITED_ANSWER)))));
        when(model.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage(CITED_ANSWER))))));

        if (streaming) service.streamFollowUp(1L, 2L, "네, 관련 판례를 검색해 주세요.", true).blockLast();
        else service.prepareFollowUp(1L, 2L, "네, 관련 판례를 검색해 주세요.", true);

        verify(routing).plan(eq(original), any(), eq("1:2"));
        verify(routing, never()).plan(eq("네, 관련 판례를 검색해 주세요."), any(), anyString());
        verify(casePipeline).search(any(), any(), anyString(), anyInt(), any(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void summaryCannotReopenSearchOrConsentAfterRoomAnswerPlan(boolean consent) {
        ownRoom();
        when(messages.findByChatRoomIdOrderByTimestampAscIdAsc(2L)).thenReturn(List.of(
                message(1L, Message.Sender.USER, "이전 사건 판례를 찾아줘"),
                message(2L, Message.Sender.BOT, "이전 판례 검색을 진행할까요?")));
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            var options = (org.springframework.ai.model.tool.ToolCallingChatOptions) ((Prompt) call.getArgument(0)).getOptions();
            assertThat(options.getToolCallbacks()).extracting(c -> c.getToolDefinition().name()).isEmpty();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(CITED_ANSWER))));
        });
        when(model.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage(CITED_ANSWER))))));
        assertThat(service.prepareFollowUp(1L, 2L, "정리해줘", consent).caseSearchRequested()).isFalse();
        assertThat(service.streamFollowUp(1L, 2L, "정리해줘", consent).blockLast().followUp().caseSearchRequested()).isFalse();
        verify(casePipeline, never()).search(any(), any(), anyString(), anyInt(), any(), any());
    }

    private void routeSearch() {
        when(routing.plan(anyString(), any(), anyString())).thenReturn(new com.MoleLaw_backend.chat.dto.answer.FollowUpCasePlan(
                com.MoleLaw_backend.chat.dto.answer.FollowUpCasePlan.Action.SEARCH_CASES, EVIDENCE_ID, "판례 검색", "최신 요청의 명시적 판례 검색"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 1200, 10000})
    void consentedSearchExecutesAndFeedsRankedCaseBodyToFinalWriterWithoutOptionalToolPreparation(int repetitions) {
        ownRoom(); when(messages.findByChatRoomIdOrderByTimestampAscIdAsc(2L)).thenReturn(List.of());
        String caseUuid = "00000000-0000-0000-0000-000000000098";
        var body = new EvidenceItem(caseUuid, "CASE_BODY", "114109", "검증용 지정 사건",
                "https://www.law.go.kr", "지정 사건의 검증용 실제 본문".repeat(repetitions));
        boolean oversized = repetitions == 10000;
        var candidate = new com.MoleLaw_backend.law.dto.tool.LegalToolContracts.Candidate("114109", null, body.title(), null,
                "검증 사건번호", "20260101", "검증법원");
        when(casePipeline.search(any(), any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn(new com.MoleLaw_backend.law.dto.retrieval.CaseSearchOutcome("AVAILABLE", List.of(candidate),
                        java.util.Map.of(candidate.id(), body), List.of(), 0, 1, 1, false, false, false));
        when(routing.plan(org.mockito.ArgumentMatchers.anyString(), any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new com.MoleLaw_backend.chat.dto.answer.FollowUpCasePlan(
                        com.MoleLaw_backend.chat.dto.answer.FollowUpCasePlan.Action.SEARCH_CASES,
                        EVIDENCE_ID, "지정 사건 판결", "사용자가 요청한 새 판결 검색"));
        when(model.stream(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            assertThat(prompt.getUserMessage().getText()).contains("SEARCH_CASES");
            if (oversized) assertThat(prompt.getUserMessage().getText()).contains("판결문 일부 발췌", "실제 본문").doesNotContain(body.content());
            else assertThat(prompt.getUserMessage().getText()).contains(body.content());
            return reactor.core.publisher.Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "찾은 판결입니다 [[" + caseUuid + "]]")))));
        });
        var updates = service.streamFollowUp(1L, 2L, "지정 사건 찾아줘", true).collectList().block(java.time.Duration.ofSeconds(5));
        var answer = updates.get(updates.size() - 1).followUp();
        assertThat(answer.newEvidence()).containsExactly(body);
        assertThat(answer.caseSearchRequested()).isFalse();
        verify(casePipeline).search(any(), any(), eq("지정 사건 판결"), eq(1), any(), any());
        verify(model, never()).call(any(Prompt.class));
    }
    @Test void newCaseSearchWithoutConsentRoutesToConsentInsteadOfReadingOldCaseOrGeneratingAdvice() {
        ownRoom();
        var law = new EvidenceItem(EVIDENCE_ID, "LAW_CHUNK", "law-1", "건축법", "https://www.law.go.kr", "구조안전 기준");
        var oldCase = new EvidenceItem("00000000-0000-0000-0000-000000000099", "CASE_BODY", "176175", "옹벽 사건",
                "https://www.law.go.kr", "{\"사건번호\":\"2013노433\",\"판결요지\":\"옹벽 하중조건 검토\"}");
        when(evidence.load(1L, 2L)).thenReturn(List.of(law, oldCase));
        when(routing.plan(org.mockito.ArgumentMatchers.anyString(), any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new com.MoleLaw_backend.chat.dto.answer.FollowUpCasePlan(
                        com.MoleLaw_backend.chat.dto.answer.FollowUpCasePlan.Action.SEARCH_CASES,
                        EVIDENCE_ID, "삼풍백화점 판결", "지정한 사건의 직접 판결을 새로 찾아야 함"));
        var sync = service.prepareFollowUp(1L, 2L, "삼풍백화점 판례를 찾아줘", false);
        assertThat(sync.caseSearchRequested()).isTrue();
        assertThat(sync.response().getAnswer()).contains("삼풍백화점 판결", "검색을 진행할까요?")
                .doesNotContain("설계변경", "확인해야 합니다");
        var updates = service.streamFollowUp(1L, 2L, "삼풍백화점 판결", false).collectList().block(java.time.Duration.ofSeconds(5));
        assertThat(updates.get(updates.size() - 1).followUp().caseSearchRequested()).isTrue();
        verify(model, never()).call(any(Prompt.class));
        verify(model, never()).stream(any(Prompt.class));
        verify(casePipeline, never()).search(any(), any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }
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
        routeSearch();
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
        verify(model, never()).call(any(Prompt.class));
        verifyNoInteractions(collector);
    }

    @Test
    void streamingConsentUsesServerGroundedQuestionAndDoesNotGrantSearchConsent() {
        routeSearch();
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
        assertThat(result.response().getAnswer()).contains("법령 근거를 자동 선택하지 않고", "동의하신 경우에만")
                .doesNotContain("[[");
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
    private final FollowUpCasePlanner routing = mock(FollowUpCasePlanner.class);

    @BeforeEach
    void setUp() {
        when(routing.plan(org.mockito.ArgumentMatchers.anyString(), any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new com.MoleLaw_backend.chat.dto.answer.FollowUpCasePlan(
                        com.MoleLaw_backend.chat.dto.answer.FollowUpCasePlan.Action.ANSWER_FROM_ROOM, null, "", "기존 근거 설명"));
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("준비 완료")))));
        var memory = new PersistedChatMemory(messages, rooms, 20, TestEncryption.create());
        var builder = new AiConfig().baseAiChatClientBuilder(model);
        var config = new AnswerChatClientConfig();
        service = new LegalAnswerService(collector, ChatClient.builder(model).build(),
                config.followUpAnswerChatClient(config.legalAnswerChatClient(builder, "gpt-5.4-mini", "medium", 16384,
                        mock(com.MoleLaw_backend.common.ai.AiPrompts.class)), memory, "gpt-5.4-mini", 16384), evidence,
                new EvidencePromptBuilder(), new CitationValidator(), new EvidenceRenderer(), new FollowUpCaseToolsFactory(
                        mock(com.MoleLaw_backend.law.service.tool.LegalApiToolService.class),
                        mock(com.MoleLaw_backend.law.service.tool.CaseEvidenceReader.class),
                        casePipeline), routing, new CaseSearchQuestionResolver(messages, TestEncryption.create()));
        when(evidence.load(1L, 2L)).thenReturn(List.of(new EvidenceItem(EVIDENCE_ID,
                "LAW_CHUNK", "law-1", "Civil law article", "https://www.law.go.kr/law/1", "original statute text")));
    }

    @Test
    void consentRequestUsesGroundedServerQuestionWithoutRequiringAnUncitedModelAnswer() {
        routeSearch();
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
        assertThat(result.response().getAnswer()).contains("법령 근거를 자동 선택하지 않고", "동의하신 경우에만")
                .doesNotContain("[[");
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
