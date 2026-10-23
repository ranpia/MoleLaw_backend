package com.MoleLaw_backend.common.config;

import com.MoleLaw_backend.auth.security.CustomUserDetails;
import com.MoleLaw_backend.chat.repository.ChatRoomRepository;
import com.MoleLaw_backend.chat.repository.MessageRepository;
import com.MoleLaw_backend.chat.repository.ChatEvidenceSnapshotRepository;
import com.MoleLaw_backend.law.repository.LegalSourceIndexRepository;
import com.MoleLaw_backend.law.service.search.LawSearchService;
import com.MoleLaw_backend.law.dto.request.LawSearchFilters;
import com.MoleLaw_backend.user.entity.User;
import com.MoleLaw_backend.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP embedding, Qdrant, H2 persistence and application services; only LLM/OpenLaw are fixtures. */
@Tag("postman-simulation")
@EnabledIfEnvironmentVariable(named = "LAW_VECTOR_SIMULATION_URL", matches = "http://127\\.0\\.0\\.1:[0-9]+")
@SpringBootTest(properties = {
        "app.law-vector.enabled=true", "app.law-vector.dimensions=2", "app.law-vector.api-key=",
        "app.law-vector.collection-name=molelaw_service_consultation_simulation",
        "spring.datasource.url=jdbc:h2:mem:initial-consultation-simulation;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestRefreshTokenStoreConfig.class)
class InitialConsultationSimulationTest {
    static final GenericContainer<?> QDRANT = new GenericContainer<>(DockerImageName.parse("qdrant/qdrant:v1.19.1"))
            .withExposedPorts(6333).waitingFor(Wait.forHttp("/readyz").forPort(6333));

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        QDRANT.start();
        registry.add("app.law-vector.qdrant-base-url", () -> "http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333));
        registry.add("app.law-vector.embedding-base-url", () -> System.getenv("LAW_VECTOR_SIMULATION_URL"));
    }
    @AfterAll static void cleanup() { QDRANT.stop(); }

    @MockitoBean ChatModel model;
    @MockitoBean EmbeddingModel unusedOpenAiEmbeddings;
    @MockitoBean LawSearchService laws;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired ChatRoomRepository rooms;
    @Autowired MessageRepository messages;
    @Autowired ChatEvidenceSnapshotRepository snapshots;
    @Autowired LegalSourceIndexRepository indexes;

    @Test
    void coldQuestionCollectsIndexesAnswersAndCommitsOneTurnWhileInvalidAnswerCommitsNothing() throws Exception {
        var owner = users.saveAndFlush(User.builder().email("flow-check@example.com").password("test-hash")
                .nickname("검증 사용자").provider("local").build());
        var principal = new CustomUserDetails(owner);
        String detail = """
                {"기본정보":{"법령명_한글":"검증법","법령ID":"1","시행일자":"20260101"},
                 "조문":{"조문단위":{"조문번호":"1","조문내용":"이 문장은 실제 법률이 아닌 순차 실행 검증용 자료입니다."}}}
                """;
        when(laws.searchPage(eq("검증법"), eq(1), isNull(), eq(1), eq(20), any(LawSearchFilters.class)))
                .thenReturn("""
                    {"LawSearch":{"totalCnt":"1","page":"1","law":{"법령일련번호":"123","법령ID":"1",
                    "법령명한글":"검증법","시행일자":"20260101"}}}
                    """);
        when(laws.getLawDetailByMst("123", "20260101")).thenReturn(mapper.readTree(detail));
        when(laws.searchPage(eq("옛검증법"), eq(1), isNull(), eq(1), eq(20), any(LawSearchFilters.class)))
                .thenReturn("{\"LawSearch\":{\"totalCnt\":\"0\"}}");
        List<String> stages = new ArrayList<>();
        AtomicBoolean invalidAnswer = new AtomicBoolean();
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            String userText = prompt.getUserMessage().getText();
            String output;
            if (prompt.getSystemMessage().getText().contains("키워드 추출 도우미")) {
                stages.add("ANALYSIS");
                output = "{\"keywords\":[\"검증법\"],\"summary\":\"검증 상담\",\"ministry\":\"법무부\"}";
            } else if (prompt.getSystemMessage().getText().contains("검색 계획을 분류")) {
                stages.add("QUESTION_PLAN");
                output = "{\"type\":\"LAW_EXPLANATION\",\"responseScope\":\"GENERAL_GUIDANCE\",\"searchQueries\":[\"검증법 근거\"],\"lawSearches\":[],\"missingFacts\":[]}";
            } else if (prompt.getSystemMessage().getText().contains("근거 본문 읽기 계획자")) {
                stages.add("EVIDENCE_READ");
                var input = mapper.readTree(userText);
                output = "{\"evidenceIds\":[\"" + input.path("candidateCatalog").get(0).path("id").asText() + "\"]}";
            } else if (prompt.getSystemMessage().getText().contains("법령 후보 적합성 심사자")) {
                stages.add("CANDIDATE_REVIEW");
                var input = mapper.readTree(userText);
                output = "{\"rankings\":[{\"candidateId\":\""
                        + input.path("candidates").get(0).path("id").asText() + "\",\"score\":95,\"reason\":\"관련\"}]}";
            } else if (userText.startsWith("{")) {
                var input = mapper.readTree(userText);
                var evidence = input.path("evidence");
                if (evidence.isEmpty()) {
                    boolean emptyObserved = false;
                    for (var attempt : input.path("searchAttempts")) {
                        if ("LAW_SEARCH".equals(attempt.path("kind").asText())
                                && "NO_RESULTS".equals(attempt.path("outcome").asText())) emptyObserved = true;
                    }
                    stages.add(emptyObserved ? "REPLAN_LAW" : "COLLECT_LAW");
                    String query = emptyObserved ? "검증법" : "옛검증법";
                    output = "{\"action\":\"COLLECT_LAW\",\"reason\":\"원문 필요\",\"missingIssues\":[\"근거\"],\"evidenceIds\":[],\"searchQueries\":[],\"lawSearches\":[{\"query\":\""
                            + query + "\",\"field\":\"TITLE\",\"exactName\":true,\"ministry\":null}]}";
                } else {
                    stages.add("ANSWER_READY");
                    output = "{\"action\":\"ANSWER_READY\",\"reason\":\"충분함\",\"missingIssues\":[],\"evidenceIds\":[\""
                            + evidence.get(0).path("id").asText() + "\"],\"searchQueries\":[],\"lawSearches\":[]}";
                }
            } else {
                stages.add("ANSWER");
                var citation = Pattern.compile("\\[\\[([0-9a-f-]{36})]]").matcher(userText);
                if (!citation.find()) throw new AssertionError("Server evidence was not supplied to the answer client");
                output = invalidAnswer.get() ? "잘못된 인용 [[00000000-0000-0000-0000-000000000099]]"
                        : "검증용 첫 답변 [[" + citation.group(1) + "]]";
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(output))));
        });

        var response = mvc.perform(post("/api/chat-rooms").with(user(principal)).with(csrf())
                .contentType("application/json").content("{\"content\":\"검증법의 근거를 설명해주세요\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages[0].sender").value("USER"))
                .andExpect(jsonPath("$.messages[1].sender").value("BOT"))
                .andExpect(jsonPath("$.messages[2].sender").value("INFO")).andReturn();
        long roomId = mapper.readTree(response.getResponse().getContentAsString()).path("id").asLong();
        assertThat(stages).containsExactly("ANALYSIS", "QUESTION_PLAN", "COLLECT_LAW", "REPLAN_LAW", "CANDIDATE_REVIEW", "EVIDENCE_READ", "ANSWER_READY", "ANSWER");
        assertThat(rooms.count()).isEqualTo(1);
        assertThat(messages.count()).isEqualTo(2);
        assertThat(snapshots.count()).isEqualTo(1);
        assertThat(indexes.findAll()).singleElement().satisfies(index -> assertThat(index.getStatus()).isEqualTo("COMPLETE"));
        assertThat(messages.findAll()).allSatisfy(message -> assertThat(message.getContent()).startsWith("gcm1."));
        mvc.perform(get("/api/chat-rooms/" + roomId + "/messages").with(user(principal)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[1].content").value(org.hamcrest.Matchers.startsWith("검증용 첫 답변")));

        invalidAnswer.set(true);
        mvc.perform(post("/api/chat-rooms").with(user(principal)).with(csrf()).contentType("application/json")
                .content("{\"content\":\"검증법의 근거를 설명해주세요\"}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("GPT_API_FAILURE"));
        assertThat(rooms.count()).isEqualTo(1);
        assertThat(messages.count()).isEqualTo(2);
        assertThat(snapshots.count()).isEqualTo(1);
        verify(laws, times(1)).getLawDetailByMst("123", "20260101");
    }
}
