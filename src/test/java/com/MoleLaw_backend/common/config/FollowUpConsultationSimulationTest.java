package com.MoleLaw_backend.common.config;

import com.MoleLaw_backend.auth.security.CustomUserDetails;
import com.MoleLaw_backend.chat.dto.answer.PreparedLegalAnswer;
import com.MoleLaw_backend.chat.dto.response.LegalAnswerResponse;
import com.MoleLaw_backend.chat.repository.ChatCaseEvidenceRepository;
import com.MoleLaw_backend.chat.repository.ChatEvidenceSnapshotRepository;
import com.MoleLaw_backend.chat.repository.MessageRepository;
import com.MoleLaw_backend.chat.service.ChatTurnService;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.law.repository.LegalSourceRepository;
import com.MoleLaw_backend.user.entity.User;
import com.MoleLaw_backend.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real application/security/Advisor/ToolCallbacks/H2 and HTTP precedent transport; model responses are fixtures. */
@Tag("postman-simulation")
@EnabledIfEnvironmentVariable(named = "CASE_SIMULATION_URL", matches = "http://127\\.0\\.0\\.1:[0-9]+")
@SpringBootTest(properties = {"openlaw.api-key=synthetic-key",
        "spring.datasource.url=jdbc:h2:mem:follow-up-simulation;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestRefreshTokenStoreConfig.class)
class FollowUpConsultationSimulationTest {
    @DynamicPropertySource static void source(DynamicPropertyRegistry properties) {
        properties.add("openlaw.case-base-url", () -> System.getenv("CASE_SIMULATION_URL") + "/DRF/");
    }
    @MockitoBean ChatModel model;
    @MockitoBean EmbeddingModel embeddings;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired ChatTurnService turns;
    @Autowired MessageRepository messages;
    @Autowired ChatEvidenceSnapshotRepository snapshots;
    @Autowired ChatCaseEvidenceRepository cases;
    @Autowired LegalSourceRepository sources;

    private ToolCallback tool(Prompt prompt, String name) {
        return ((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks().stream()
                .filter(callback -> callback.getToolDefinition().name().equals(name)).findFirst().orElseThrow();
    }

    @Test void asksConsentThenCollectsCitesPersistsReusesAndSeparatelyServesEvidence() throws Exception {
        var owner = users.saveAndFlush(User.builder().email("follow-up@example.test").nickname("상담자")
                .password("hash").provider("local").build());
        var other = users.saveAndFlush(User.builder().email("other@example.test").nickname("다른 사용자")
                .password("hash").provider("local").build());
        var principal = new CustomUserDetails(owner);
        String lawId = UUID.randomUUID().toString();
        var law = new EvidenceItem(lawId, "LAW_CHUNK", "123:1", "검증법 제1조(목적)",
                "https://www.law.go.kr/법령?lsiSeq=123", "가상의 법령 근거 본문");
        var room = turns.saveInitialTurn(owner, "검증 상담", "첫 질문", new PreparedLegalAnswer(
                new LegalAnswerResponse("첫 답변 [[" + lawId + "]]", ""), List.of(law)));
        String path = "/api/chat-rooms/" + room.getId();
        String snapshot = snapshots.findById(room.getId()).orElseThrow().getEvidenceJson();
        AtomicInteger mode = new AtomicInteger();
        AtomicReference<String> caseEvidenceId = new AtomicReference<>();
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            assertThat(prompt.getInstructions().stream().map(message -> message.getText()).toList()).contains("첫 질문", "첫 답변 [[" + lawId + "]]");
            String output;
            if (mode.get() == 0) {
                assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks())
                        .noneMatch(callback -> callback.getToolDefinition().name().equals("searchRoomCases"));
                var requested = mapper.readTree(tool(prompt, "requestCaseSearchConsent").call(
                        mapper.writeValueAsString(java.util.Map.of("lawEvidenceId", lawId, "reason", "쟁점 판례 확인"))));
                assertThat(requested.path("status").asText()).isEqualTo("CONSENT_REQUIRED");
                output = "기존 조문 범위의 안내입니다. [[" + lawId + "]]";
            } else if (mode.get() == 2) {
                assertThat(prompt.getInstructions().stream().map(message -> message.getText()).toList())
                        .anyMatch(text -> text.contains("판례를 찾아주세요"));
                var read = mapper.readTree(tool(prompt, "readRoomCase").call("{\"caseId\":\"901\"}"));
                assertThat(read.path("evidence").path("id").asText()).isEqualTo(caseEvidenceId.get());
                output = "이전 판례를 이어 설명합니다. [[" + caseEvidenceId.get() + "]]";
            } else {
                String query = mode.get() == 3 ? "invalid-answer" : mode.get() == 4 ? "unavailable" : "쟁점 & 예외+{}";
                var result = mapper.readTree(tool(prompt, "searchRoomCases").call(mapper.writeValueAsString(
                        java.util.Map.of("lawEvidenceId", lawId, "query", query, "page", 1))));
                if (mode.get() == 4) {
                    assertThat(result.path("status").asText()).isEqualTo("UNAVAILABLE");
                    output = "판례 조회에 실패해 기존 조문 범위로 안내합니다. [[" + lawId + "]]";
                } else {
                    String id = result.path("candidates").get(0).path("id").asText();
                    var body = mapper.readTree(tool(prompt, "readRoomCase").call("{\"caseId\":\"" + id + "\"}"));
                    assertThat(body.path("status").asText()).isEqualTo("READ");
                    String evidenceId = body.path("evidence").path("id").asText();
                    if (mode.get() == 1) caseEvidenceId.set(evidenceId);
                    output = mode.get() == 3 ? "잘못된 인용 [[00000000-0000-0000-0000-000000000099]]"
                            : "읽은 판례로 보강한 답변입니다. [[" + evidenceId + "]]";
                }
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(output))));
        });

        mvc.perform(post(path + "/messages").with(user(principal)).with(csrf()).contentType("application/json")
                .content("{\"content\":\"판례도 필요한가요?\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.caseSearchRequested").value(true));
        assertThat(sources.count()).isZero();
        mode.set(1);
        mvc.perform(post(path + "/messages").with(user(principal)).with(csrf()).contentType("application/json")
                .content("{\"content\":\"판례를 찾아주세요\",\"allowCaseSearch\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.caseSearchRequested").value(false));
        assertThat(cases.count()).isEqualTo(1);
        assertThat(sources.count()).isEqualTo(1);
        assertThat(messages.count()).isEqualTo(6);
        assertThat(messages.findAll()).allSatisfy(message -> assertThat(message.getContent()).startsWith("gcm1."));
        assertThat(messages.findAll()).filteredOn(message -> message.isCaseSearchConsent()).hasSize(1);
        assertThat(snapshots.findById(room.getId()).orElseThrow().getEvidenceJson()).isEqualTo(snapshot);
        mvc.perform(get(path + "/evidence").with(user(principal))).andExpect(status().isOk())
                .andExpect(jsonPath("$[1].id").value(caseEvidenceId.get())).andExpect(jsonPath("$[1].kind").value("CASE_BODY"))
                .andExpect(jsonPath("$[1].content").doesNotExist());
        mvc.perform(get(path + "/evidence/" + caseEvidenceId.get()).with(user(principal))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("검증용 본문")));
        mode.set(2);
        mvc.perform(post(path + "/messages").with(user(principal)).with(csrf()).contentType("application/json")
                .content("{\"content\":\"그 판례를 더 설명해줘\"}"))
                .andExpect(status().isOk());
        assertThat(sources.count()).isEqualTo(1);
        assertThat(messages.count()).isEqualTo(8);

        mode.set(3);
        mvc.perform(post(path + "/messages").with(user(principal)).with(csrf()).contentType("application/json")
                .content("{\"content\":\"다른 판례도 찾아줘\",\"allowCaseSearch\":true}"))
                .andExpect(status().isInternalServerError());
        assertThat(cases.count()).isEqualTo(1);
        assertThat(messages.count()).isEqualTo(8);
        mode.set(4);
        mvc.perform(post(path + "/messages").with(user(principal)).with(csrf()).contentType("application/json")
                .content("{\"content\":\"판례 확인\",\"allowCaseSearch\":true}"))
                .andExpect(status().isOk());
        assertThat(cases.count()).isEqualTo(1);
        assertThat(messages.count()).isEqualTo(10);
        mvc.perform(get(path + "/evidence/" + UUID.randomUUID()).with(user(principal))).andExpect(status().isNotFound());
        int calls = mockingDetails(model).getInvocations().size();
        for (String suffix : List.of("/evidence", "/evidence/" + caseEvidenceId.get()))
            mvc.perform(get(path + suffix).with(user(new CustomUserDetails(other)))).andExpect(status().isForbidden());
        mvc.perform(post(path + "/messages").with(user(new CustomUserDetails(other))).with(csrf())
                .contentType("application/json").content("{\"content\":\"질문\",\"allowCaseSearch\":true}"))
                .andExpect(status().isForbidden());
        assertThat(mockingDetails(model).getInvocations()).hasSize(calls);
        mvc.perform(get(path + "/evidence")).andExpect(status().isUnauthorized());
        mvc.perform(delete(path).with(user(principal)).with(csrf())).andExpect(status().isNoContent());
        assertThat(cases.count()).isZero();
        assertThat(messages.count()).isZero();
    }
}
