package com.MoleLaw_backend.chat.controller;

import com.MoleLaw_backend.auth.security.CustomUserDetails;
import com.MoleLaw_backend.common.config.TestRefreshTokenStoreConfig;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.law.dto.retrieval.CollectedLegalEvidence;
import com.MoleLaw_backend.law.dto.retrieval.QuestionRetrievalPlan;
import com.MoleLaw_backend.law.dto.retrieval.RetrievalObserver;
import com.MoleLaw_backend.law.dto.response.KeywordExtractionResponse;
import com.MoleLaw_backend.law.service.analysis.KeywordExtractionService;
import com.MoleLaw_backend.law.service.retrieval.LegalEvidenceCollector;
import com.MoleLaw_backend.user.entity.User;
import com.MoleLaw_backend.user.repository.UserRepository;
import com.MoleLaw_backend.chat.repository.ChatRoomRepository;
import com.MoleLaw_backend.chat.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:sse-test;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER")
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("test")
@Import(TestRefreshTokenStoreConfig.class)
class InitialAnswerStreamIntegrationTest {
    @Test void ownerCanDeleteSavedEvidenceButCannotDeleteItTwice() throws Exception {
        Long roomId = seedRoom();
        mvc.perform(delete("/api/chat-rooms/" + roomId + "/evidence/" + id).with(user(principal)).with(csrf()))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        mvc.perform(get("/api/chat-rooms/" + roomId + "/evidence").with(user(principal)))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/chat-rooms/" + roomId + "/evidence/" + id).with(user(principal)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/chat-rooms/" + roomId + "/evidence/" + id).with(user(principal)).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test void deletingEvidenceRequiresCsrfAndRejectsMalformedOrUnknownIds() throws Exception {
        Long roomId = seedRoom();
        mvc.perform(delete("/api/chat-rooms/" + roomId + "/evidence/" + id).with(user(principal)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/chat-rooms/" + roomId + "/evidence/bad-id").with(user(principal)).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/chat-rooms/" + roomId + "/evidence/" + UUID.randomUUID()).with(user(principal)).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test void followUpRepairsCitationOnceAndSavesOnlyTheCorrectedCompletion() throws Exception {
        Long roomId = seedRoom();
        long before = messages.count();
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            return response(prompt.getSystemMessage().getText().contains("후속판례실행계획자")
                    ? "{\"action\":\"ANSWER_FROM_ROOM\",\"lawEvidenceId\":null,\"query\":\"\",\"reason\":\"일반 설명 요청\"}"
                    : "교정한 설명 [[" + id + "]]");
        });
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("출처 표기가 빠진 설명")));
        var result = mvc.perform(post("/api/chat-rooms/" + roomId + "/messages/ndjson").accept("application/x-ndjson")
                .with(user(principal)).with(csrf()).contentType("application/json").content("{\"content\":\"설명해줘\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(10000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        assertThat(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("ANSWER_REPAIR", "\"type\":\"completed\"", "교정한 설명")
                .doesNotContain("\"type\":\"error\"", "CASE_BODY_READY");
        assertThat(messages.count()).isEqualTo(before + 2);
        verify(model, times(2)).call(any(Prompt.class));
    }

    @Test void selectionWithoutConsentIsRejectedBeforeStartingStream() throws Exception {
        Long roomId = seedRoom();
        long before = messages.count();
        mvc.perform(post("/api/chat-rooms/" + roomId + "/messages/ndjson").accept("application/x-ndjson")
                .with(user(principal)).with(csrf()).contentType("application/json")
                .content("{\"content\":\"검색\",\"caseSearchLawEvidenceIds\":[\"" + id + "\"]}"))
                .andExpect(status().isBadRequest()).andExpect(request().asyncNotStarted());
        assertThat(messages.count()).isEqualTo(before);
        verify(model, never()).call(any(Prompt.class));
    }

    @Test void unknownSelectedLawEmitsInvalidRequestWithoutSavingOrCallingModel() throws Exception {
        Long roomId = seedRoom();
        long before = messages.count();
        var result = mvc.perform(post("/api/chat-rooms/" + roomId + "/messages/ndjson").accept("application/x-ndjson")
                .with(user(principal)).with(csrf()).contentType("application/json")
                .content("{\"content\":\"검색\",\"allowCaseSearch\":true,\"caseSearchLawEvidenceIds\":[\""
                        + UUID.randomUUID() + "\"]}"))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(10000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        assertThat(result.getResponse().getContentAsString()).contains("\"type\":\"error\"", "INVALID_REQUEST")
                .doesNotContain("\"type\":\"completed\"");
        assertThat(messages.count()).isEqualTo(before);
        verify(model, never()).call(any(Prompt.class));
    }

    @MockitoBean ChatModel model;
    @MockitoBean EmbeddingModel embeddings;
    @MockitoBean LegalEvidenceCollector collector;
    @MockitoBean KeywordExtractionService keywords;
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ChatRoomRepository rooms;
    @Autowired MessageRepository messages;
    @Autowired com.MoleLaw_backend.auth.security.JwtUtil jwt;
    @Autowired com.MoleLaw_backend.chat.service.ChatTurnService turns;
    private CustomUserDetails principal;
    private final String id = "00000000-0000-0000-0000-000000000001";

    @BeforeEach void fixture() {
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            return response(prompt.getSystemMessage().getText().contains("후속판례실행계획자")
                    ? "{\"action\":\"ANSWER_FROM_ROOM\",\"lawEvidenceId\":null,\"query\":\"\",\"reason\":\"기존 근거 설명\"}"
                    : "준비 완료");
        });
        principal = new CustomUserDetails(users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@example.test")
                .nickname("검증자").password("hash").provider("local").build()));
        var evidence = new EvidenceItem(id, "LAW_CHUNK", "270551:1", "검증 조문",
                "https://www.law.go.kr/법령?lsiSeq=270551", "검증용 합성 본문");
        var collected = new CollectedLegalEvidence(new QuestionRetrievalPlan(QuestionRetrievalPlan.Type.LAW_EXPLANATION,
                QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE, List.of("질문"), List.of(), List.of()), List.of(evidence));
        when(collector.collectForAnswer(anyString(), any())).thenAnswer(call -> {
            RetrievalObserver observer = call.getArgument(1);
            observer.progress("QUESTION_PLANNING");
            observer.evidence(List.of(evidence));
            return collected;
        });
        when(keywords.extractKeywords(anyString())).thenReturn(new KeywordExtractionResponse(List.of("검증"), "검증 상담", null));
    }

    @Test void sendsInfoAndModelDeltasBeforeCompletionThenAtomicallySavesRoom() throws Exception {
        long roomCount = rooms.count(), messageCount = messages.count();
        var finish = Sinks.<ChatResponse>one();
        when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(response("읽은 조문에 따르면 ")), finish.asMono()));
        var result = mvc.perform(post("/api/chat-rooms/stream").accept("text/event-stream").with(user(principal)).with(csrf())
                .contentType("application/json").content("{\"content\":\"질문\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        long until = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (!result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("answer_delta")
                && System.nanoTime() < until) Thread.sleep(10);
        String early = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(early).contains("event:progress", "event:info", "event:answer_delta", "lsInfoP.do?lsiSeq=270551")
                .doesNotContain("event:completed");
        assertThat(rooms.count()).isEqualTo(roomCount);
        assertThat(messages.count()).isEqualTo(messageCount);
        finish.tryEmitValue(response("설명합니다 [[" + id + "]]"));
        result.getAsyncResult(10000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"));
        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("event:completed", "\"room\":", "\"response\":");
        assertThat(rooms.count()).isEqualTo(roomCount + 1);
        assertThat(messages.count()).isEqualTo(messageCount + 2);
    }

    @Test void invalidCitationReturnsErrorEventAndNeverSavesTurn() throws Exception {
        long roomCount = rooms.count(), messageCount = messages.count();
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("잘못된 인용 [[00000000-0000-0000-0000-000000000099]]")));
        var result = mvc.perform(post("/api/chat-rooms/stream").accept("text/event-stream").with(user(principal)).with(csrf())
                .contentType("application/json").content("{\"content\":\"질문\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(10000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        assertThat(result.getResponse().getContentAsString()).contains("event:error", "GPT_API_FAILURE").doesNotContain("event:completed");
        assertThat(rooms.count()).isEqualTo(roomCount);
        assertThat(messages.count()).isEqualTo(messageCount);
    }

    @Test void requiresAuthenticationCsrfAndValidQuestionBeforeStartingWork() throws Exception {
        mvc.perform(post("/api/legal-answers/stream").with(csrf()).contentType("application/json").content("{\"query\":\"질문\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/chat-rooms/stream").with(user(principal)).contentType("application/json").content("{\"content\":\"질문\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/legal-answers/stream").with(user(principal)).with(csrf())
                .contentType("application/json").content("{\"query\":\" \"}"))
                .andExpect(status().isBadRequest());
        verify(model, never()).stream(any(Prompt.class));
        verify(model, never()).call(any(Prompt.class));
    }

    @Test void ndjsonFlushesIndividualJsonLinesBeforeModelCompletionAndSavesOnlyAtEnd() throws Exception {
        long roomCount = rooms.count();
        var finish = Sinks.<ChatResponse>one();
        when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(response("조문 \"내용\"\n다음 줄 ")), finish.asMono()));
        var result = mvc.perform(post("/api/chat-rooms/ndjson").accept("application/x-ndjson")
                .with(user(principal)).with(csrf()).contentType("application/json").content("{\"content\":\"질문\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        long until = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (!result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("answer_delta")
                && System.nanoTime() < until) Thread.sleep(10);
        String early = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(early).contains("\"type\":\"progress\"", "\"type\":\"info\"", "\"type\":\"answer_delta\"")
                .contains("\"type\":\"evidence\"", "\"state\":\"PREVIEW\"")
                .doesNotContain("event:", "data:", "\"type\":\"completed\"");
        assertThat(rooms.count()).isEqualTo(roomCount);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String line : early.strip().split("\\r?\\n")) {
            assertThat(mapper.readTree(line).path("type").asText()).isNotBlank();
        }
        finish.tryEmitValue(response("근거를 설명합니다 [[" + id + "]]"));
        result.getAsyncResult(10000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"));
        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var lines = body.strip().split("\\r?\\n");
        for (String line : lines) assertThat(mapper.readTree(line).isObject()).isTrue();
        assertThat(body).contains("\"state\":\"SAVED\"");
        var completed = mapper.readTree(lines[lines.length - 1]);
        assertThat(completed.path("type").asText()).isEqualTo("completed");
        assertThat(completed.path("room").path("id").asLong()).isPositive();
        assertThat(rooms.count()).isEqualTo(roomCount + 1);
    }

    @Test void ndjsonAliasReturnsErrorLineWithoutSavingInvalidCitation() throws Exception {
        long roomCount = rooms.count();
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("잘못된 인용 [[00000000-0000-0000-0000-000000000099]]")));
        var result = mvc.perform(post("/api/chat-rooms/ndjson").accept("application/x-ndjson")
                .with(user(principal)).with(csrf()).contentType("application/json").content("{\"content\":\"질문\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(10000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"));
        assertThat(result.getResponse().getContentAsString()).contains("\"type\":\"error\"", "GPT_API_FAILURE")
                .doesNotContain("event:", "\"type\":\"completed\"");
        assertThat(rooms.count()).isEqualTo(roomCount);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"text/event-stream", "application/x-ndjson"})
    void actualJwtCookieRemainsAuthenticatedOnAsyncCompletion(String mediaType) throws Exception {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("본문에 따른 안내 [[" + id + "]]")));
        String path = mediaType.equals("text/event-stream") ? "/api/legal-answers/stream" : "/api/legal-answers/ndjson";
        var result = mvc.perform(post(path).accept(mediaType).with(csrf())
                .cookie(new jakarta.servlet.http.Cookie("accessToken", jwt.generateAccessToken(principal.getUserId())))
                .contentType("application/json").content("{\"query\":\"질문\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(10000);
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        assertThat(result.getResponse().getContentAsString()).contains("completed");
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test void explicitCaseSearchEmitsConsentInsteadOfGenericLawAdviceInNdjson() throws Exception {
        Long roomId = seedRoom(); long before = messages.count();
        when(model.call(any(Prompt.class))).thenReturn(response("{\"action\":\"SEARCH_CASES\",\"lawEvidenceId\":null,"
                + "\"query\":\"삼풍백화점 판결\",\"reason\":\"사용자가 지정한 판결의 새 검색 요청\"}"));
        var result = mvc.perform(post("/api/chat-rooms/" + roomId + "/messages/ndjson").accept("application/x-ndjson")
                .cookie(new jakarta.servlet.http.Cookie("accessToken", jwt.generateAccessToken(principal.getUserId())))
                .with(csrf()).contentType("application/json").content("{\"content\":\"삼풍백화점 판결을 새로 찾아줘\",\"allowCaseSearch\":false}"))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(10000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("FOLLOW_UP_ROUTING", "검색을 진행할까요?", "\"caseSearchRequested\":true")
                .doesNotContain("설계변경이 있었는지", "확인해야 합니다");
        assertThat(messages.count()).isEqualTo(before + 2);
        verify(model, never()).stream(any(Prompt.class));
    }

    @Test void followUpNdjsonStreamsNumberedCitationsAndSavesOnlyTheValidatedMessage() throws Exception {
        Long roomId = seedRoom();
        long before = messages.count();
        var finish = Sinks.<ChatResponse>one();
        when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(response("추가 설명 [[" + id.substring(0, 12))), finish.asMono()));
        var result = mvc.perform(post("/api/chat-rooms/" + roomId + "/messages/ndjson").accept("application/x-ndjson")
                .cookie(new jakarta.servlet.http.Cookie("accessToken", jwt.generateAccessToken(principal.getUserId())))
                .with(csrf()).contentType("application/json").content("{\"content\":\"추가 질문\",\"allowCaseSearch\":false}"))
                .andExpect(request().asyncStarted()).andReturn();
        long until = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (!result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("answer_delta")
                && System.nanoTime() < until) Thread.sleep(10);
        String early = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(early).contains("CONVERSATION_LOADING", "\"type\":\"info\"", "\"type\":\"answer_delta\"")
                .contains("\"type\":\"evidence\"", "\"state\":\"PREVIEW\"")
                .doesNotContain("\"type\":\"completed\"");
        var earlyMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String line : early.strip().split("\\r?\\n")) {
            var frame = earlyMapper.readTree(line);
            if (frame.path("type").asText().equals("answer_delta"))
                assertThat(frame.path("delta").asText()).doesNotContain(id.substring(0, 12));
            if (frame.path("type").asText().equals("evidence"))
                assertThat(frame.path("evidence").path("items").get(0).has("content")).isFalse();
        }
        assertThat(messages.count()).isEqualTo(before);
        finish.tryEmitValue(response(id.substring(12) + "]]"));
        result.getAsyncResult(10000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"));
        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var lines = body.strip().split("\\r?\\n");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (int i = 0; i < lines.length - 1; i++) {
            var frame = mapper.readTree(lines[i]);
            assertThat(frame.isObject()).isTrue();
            if (!frame.path("type").asText().equals("evidence")) assertThat(lines[i]).doesNotContain(id);
        }
        var last = mapper.readTree(lines[lines.length - 1]);
        assertThat(last.path("type").asText()).isEqualTo("completed");
        assertThat(last.path("messageResult").path("sender").asText()).isEqualTo("BOT");
        assertThat(last.path("messageResult").path("content").asText()).contains("[[" + id + "]]");
        assertThat(messages.count()).isEqualTo(before + 2);
        verifyNoInteractions(collector);
    }

    @Test void followUpRejectsOtherOwnersAndMissingRoomsBeforeStartingModel() throws Exception {
        Long roomId = seedRoom();
        var other = users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@example.test")
                .nickname("다른 사용자").password("hash").provider("local").build());
        mvc.perform(post("/api/chat-rooms/" + roomId + "/messages/ndjson").accept("application/x-ndjson")
                .with(user(new CustomUserDetails(other))).with(csrf()).contentType("application/json").content("{\"content\":\"질문\"}"))
                .andExpect(status().isForbidden()).andExpect(content().contentTypeCompatibleWith("application/json"));
        mvc.perform(post("/api/chat-rooms/9223372036854775807/messages/ndjson").accept("application/x-ndjson")
                .with(user(principal)).with(csrf()).contentType("application/json").content("{\"content\":\"질문\"}"))
                .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith("application/json"));
        verify(model, never()).stream(any(Prompt.class));
    }

    @Test void invalidFollowUpCitationReturnsErrorAndDoesNotSavePartialMessages() throws Exception {
        Long roomId = seedRoom();
        long before = messages.count();
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("잘못된 인용 [[00000000-0000-0000-0000-000000000099]]")));
        var result = mvc.perform(post("/api/chat-rooms/" + roomId + "/messages/ndjson").accept("application/x-ndjson")
                .with(user(principal)).with(csrf()).contentType("application/json").content("{\"content\":\"질문\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(10000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        assertThat(result.getResponse().getContentAsString()).contains("\"type\":\"error\"", "GPT_API_FAILURE")
                .doesNotContain("\"type\":\"completed\"", "00000000-0000-0000-0000-000000000099");
        assertThat(messages.count()).isEqualTo(before);
    }

    private Long seedRoom() {
        var evidence = new EvidenceItem(id, "LAW_CHUNK", "285279:1", "검증 조문",
                "https://www.law.go.kr/%EB%B2%95%EB%A0%B9?lsId=001872&lsiSeq=285279", "검증용 합성 본문");
        return turns.saveInitialTurn(principal.getUser(), "검증 상담", "최초 질문",
                new com.MoleLaw_backend.chat.dto.answer.PreparedLegalAnswer(
                        new com.MoleLaw_backend.chat.dto.response.LegalAnswerResponse("최초 답변 [[" + id + "]]", ""), List.of(evidence))).getId();
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
