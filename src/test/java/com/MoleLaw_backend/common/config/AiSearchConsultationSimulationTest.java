package com.MoleLaw_backend.common.config;

import com.MoleLaw_backend.auth.security.CustomUserDetails;
import com.MoleLaw_backend.chat.repository.ChatEvidenceSnapshotRepository;
import com.MoleLaw_backend.chat.repository.ChatRoomRepository;
import com.MoleLaw_backend.law.service.tool.LawRagSearchToolService;
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
import org.springframework.beans.factory.ObjectProvider;
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
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postman-simulation")
@EnabledIfEnvironmentVariable(named = "LAW_AI_SEARCH_SIMULATION_URL", matches = "http://127\\.0\\.0\\.1:[0-9]+")
@SpringBootTest(properties = {"app.law-retrieval.backend=AI_SEARCH", "app.law-vector.enabled=false",
        "openlaw.api-key=synthetic-key",
        "spring.datasource.url=jdbc:h2:mem:ai-consultation-simulation;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestRefreshTokenStoreConfig.class)
class AiSearchConsultationSimulationTest {
    @DynamicPropertySource static void remote(DynamicPropertyRegistry registry) {
        registry.add("app.law-ai-search.base-url", () -> System.getenv("LAW_AI_SEARCH_SIMULATION_URL") + "/DRF");
    }
    @MockitoBean ChatModel model;
    @MockitoBean EmbeddingModel unusedEmbeddings;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired ChatRoomRepository rooms;
    @Autowired ChatEvidenceSnapshotRepository snapshots;
    @Autowired ObjectProvider<LawRagSearchToolService> qdrant;

    @Test void apiArticlesProduceAStoredCitedAnswerWithoutQdrantAndProviderFailureDoesNotSave() throws Exception {
        assertThat(qdrant.getIfAvailable()).isNull();
        var owner = users.saveAndFlush(User.builder().email("ai-flow@example.com").password("test-hash")
                .nickname("AI 검색 검증").provider("local").build());
        var principal = new CustomUserDetails(owner);
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            String system = prompt.getSystemMessage().getText(), user = prompt.getUserMessage().getText(), output;
            if (system.contains("키워드 추출 도우미")) {
                output = "{\"keywords\":[\"공항\"],\"summary\":\"공항 상담\",\"ministry\":\"국토교통부\"}";
            } else if (system.contains("검색 계획을 분류")) {
                output = "{\"type\":\"LAW_DISCOVERY\",\"responseScope\":\"GENERAL_GUIDANCE\",\"searchQueries\":[\""
                        + (user.contains("denied") ? "denied" : "공항") + "\"],\"lawSearches\":[],\"missingFacts\":[]}";
                var analysis = mapper.createObjectNode().put("title", "공항 상담").put("purpose", "관련 근거 안내");
                analysis.putArray("facts");
                var issue = analysis.putArray("issues").addObject().put("id", "Q1")
                        .put("request", "관련 근거 안내").put("sourceQuote", user);
                issue.putArray("searchTerms").add(user.contains("denied") ? "denied" : "공항");
                issue.putArray("mentionedTargets"); issue.putArray("candidateTargets"); issue.putArray("missingFacts");
                analysis.putArray("relations"); analysis.putArray("ambiguities");
                output = ((com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(output)).set("analysis", analysis).toString();
            } else if (system.contains("근거 본문 읽기 계획자")) {
                var input = mapper.readTree(user);
                output = "{\"evidenceIds\":[\"" + input.path("candidateCatalog").get(0).path("id").asText() + "\"]}";
            } else if (user.startsWith("{")) {
                var input = mapper.readTree(user);
                assertThat(input.path("executionState").path("retrievalBackend").asText()).isEqualTo("AI_SEARCH");
                output = "{\"action\":\"ANSWER_READY\",\"reason\":\"검증 근거 확보\",\"missingIssues\":[],\"evidenceIds\":[\""
                        + input.path("evidence").get(0).path("id").asText() + "\"],\"searchQueries\":[],\"lawSearches\":[]}";
            } else {
                var citation = Pattern.compile("\\[\\[([0-9a-f-]{36})]]").matcher(user);
                if (!citation.find()) throw new AssertionError("Missing evidence citation");
                output = "가상 검증 답변 [[" + citation.group(1) + "]]";
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(output))));
        });
        var response = mvc.perform(post("/api/chat-rooms").with(user(principal)).with(csrf())
                .contentType("application/json").content("{\"content\":\"공항 관련 법령 안내\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages[1].sender").value("BOT")).andReturn();
        long roomId = mapper.readTree(response.getResponse().getContentAsString()).path("id").asLong();
        assertThat(snapshots.findById(roomId).orElseThrow().getEvidenceJson()).contains("LAW_AI_SEARCH", "가상 공항검증법");
        mvc.perform(get("/api/chat-rooms/" + roomId + "/messages").with(user(principal)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[1].content").value(org.hamcrest.Matchers.startsWith("가상 검증 답변")));
        mvc.perform(post("/api/chat-rooms").with(user(principal)).with(csrf())
                .contentType("application/json").content("{\"content\":\"denied 공항 질문\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("RETRIEVAL_UNAVAILABLE"));
        assertThat(rooms.count()).isEqualTo(1);
        assertThat(snapshots.count()).isEqualTo(1);
    }
}
