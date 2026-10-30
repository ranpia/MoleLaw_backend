package com.MoleLaw_backend.chat.controller;

import com.MoleLaw_backend.auth.security.CustomUserDetails;
import com.MoleLaw_backend.chat.repository.ChatRoomRepository;
import com.MoleLaw_backend.chat.repository.MessageRepository;
import com.MoleLaw_backend.common.config.TestRefreshTokenStoreConfig;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.law.dto.retrieval.CollectedLegalEvidence;
import com.MoleLaw_backend.law.dto.retrieval.QuestionRetrievalPlan;
import com.MoleLaw_backend.law.dto.retrieval.RetrievalObserver;
import com.MoleLaw_backend.law.service.retrieval.LegalEvidenceCollector;
import com.MoleLaw_backend.user.entity.User;
import com.MoleLaw_backend.user.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postman-simulation")
@EnabledIfEnvironmentVariable(named="ANSWER_STREAM_SIMULATION_URL", matches="http://127\\.0\\.0\\.1:[0-9]+")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:answer-simulation;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER",
        "app.ai.answer.max-completion-tokens=4096", "app.chat-stream.max-concurrent=4", "app.chat-stream.timeout=5s"})
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("test")
@Import(TestRefreshTokenStoreConfig.class)
class AnswerStreamSimulationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ChatRoomRepository rooms;
    @Autowired MessageRepository messages;
    @Autowired org.springframework.security.web.FilterChainProxy securityFilters;
    @Autowired com.MoleLaw_backend.chat.service.answer.InitialAnswerStreamService streams;
    @MockitoBean ChatModel model;
    @MockitoBean EmbeddingModel embedding;
    @MockitoBean LegalEvidenceCollector collector;
    @MockitoBean com.MoleLaw_backend.law.service.analysis.KeywordExtractionService keywords;
    private final ObjectMapper mapper = new ObjectMapper();
    private RestClient control;
    private CustomUserDetails principal;
    private final java.util.concurrent.atomic.AtomicBoolean adapterCancelled=new java.util.concurrent.atomic.AtomicBoolean();

    @BeforeEach void fixture() {
        securityFilters.getFilterChains().forEach(chain -> chain.getFilters().stream()
                .filter(org.springframework.security.web.header.HeaderWriterFilter.class::isInstance)
                .map(org.springframework.security.web.header.HeaderWriterFilter.class::cast)
                .forEach(filter -> filter.setShouldWriteHeadersEagerly(true)));
        connect(System.getenv("ANSWER_STREAM_SIMULATION_URL"));
        control.post().uri("/control/release").retrieve().toBodilessEntity();
        when(keywords.extractKeywords(anyString())).thenReturn(new com.MoleLaw_backend.law.dto.response.KeywordExtractionResponse(List.of("검증"),"검증 상담",null));
        principal = new CustomUserDetails(users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@example.test")
                .nickname("부하 검증").password("hash").provider("local").build()));
        var source = new EvidenceItem("00000000-0000-0000-0000-000000000001","LAW_CHUNK","fixture:1",
                "합성 근거","https://example.test/law","검증 전용 합성 본문");
        var plan = new QuestionRetrievalPlan(QuestionRetrievalPlan.Type.LAW_EXPLANATION,
                QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE,List.of("검증"),List.of(),List.of());
        when(collector.collectForAnswer(anyString(),any())).thenAnswer(call -> {
            RetrievalObserver observer=call.getArgument(1);
            observer.progress("INITIAL_SEARCH"); observer.evidence(List.of(source));
            return new CollectedLegalEvidence(plan,List.of(source));
        });
    }

    private void connect(String origin) {
        assertThat(origin).matches("http://127\\.0\\.0\\.1:[0-9]+");
        control=RestClient.create(origin+"/openai");
        var api = OpenAiApi.builder().apiKey("synthetic-key")
                .baseUrl(origin+"/openai").build();
        var real = OpenAiChatModel.builder().openAiApi(api)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).noBackoff().build()).build();
        // Only retrieval is synthetic. The real model adapter performs HTTP streaming.
        adapterCancelled.set(false);
        when(model.stream(any(Prompt.class))).thenAnswer(call -> real.stream(call.<Prompt>getArgument(0))
                .doOnCancel(() -> adapterCancelled.set(true)));
    }

    private MvcResult start(String question, boolean save, boolean sse) throws Exception {
        return mvc.perform(post(save ? (sse?"/api/chat-rooms/stream":"/api/chat-rooms/ndjson")
                        : (sse?"/api/legal-answers/stream":"/api/legal-answers/ndjson"))
                .with(user(principal)).with(csrf()).accept(sse?"text/event-stream":"application/x-ndjson")
                .contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of(save?"content":"query",question))))
                .andExpect(request().asyncStarted()).andReturn();
    }

    private String finish(MvcResult result) throws Exception {
        result.getAsyncResult(12000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        String body=result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var events=parseEvents(body,result.getResponse().getContentType().startsWith("text/event-stream"));
        assertThat(events.stream().filter(event -> List.of("completed","error").contains(event.path("type").asText())).count()).isEqualTo(1);
        assertThat(List.of("completed","error")).contains(events.get(events.size()-1).path("type").asText());
        return body;
    }

    private List<JsonNode> parseEvents(String body,boolean sse) throws Exception {
        var events=new ArrayList<JsonNode>();
        for(String line:body.split("\\r?\\n")) {
            if(line.isBlank() || (sse && !line.startsWith("data:"))) continue;
            var event=mapper.readTree(sse?line.substring(5).stripLeading():line);
            assertThat(event.isObject()).isTrue();
            assertThat(event.path("type").asText()).isIn("progress","info","evidence","answer_delta","completed","error","heartbeat");
            events.add(event);
        }
        assertThat(events).isNotEmpty();
        return events;
    }

    private JsonNode state() { return control.get().uri("/control/state").retrieve().body(JsonNode.class); }

    private void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(4).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(10);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void streamsBeforeCompletionAndPersistsOnlyAfterValidation(boolean sse) throws Exception {
        long beforeRooms=rooms.count(), beforeMessages=messages.count();
        var result=start("fixture-hold",true,sse);
        try {
            await(() -> new String(result.getResponse().getContentAsByteArray(),java.nio.charset.StandardCharsets.UTF_8).contains("answer_delta"));
            assertThat(result.getResponse().getContentAsString()).doesNotContain("completed");
            assertThat(rooms.count()).isEqualTo(beforeRooms);
            assertThat(messages.count()).isEqualTo(beforeMessages);
        } finally { control.post().uri("/control/release").retrieve().toBodilessEntity(); }
        String body=finish(result);
        assertThat(body).contains("completed","SAVED").doesNotContain("GPT_API_FAILURE");
        if (!sse) for (String line:body.strip().split("\\r?\\n")) assertThat(mapper.readTree(line).isObject()).isTrue();
        assertThat(rooms.count()).isEqualTo(beforeRooms+1);
        assertThat(messages.count()).isEqualTo(beforeMessages+2);
    }

    @ParameterizedTest @ValueSource(ints={1,2,4})
    void fixedConcurrentStreamsCompleteWithoutWritingRooms(int concurrency) throws Exception {
        long before=rooms.count();
        var requests=new ArrayList<MvcResult>();
        for(int i=0;i<concurrency;i++) requests.add(start("fixture-normal",false,false));
        for(var request:requests) assertThat(finish(request)).contains("\"type\":\"completed\"");
        assertThat(rooms.count()).isEqualTo(before);
    }

    @Test void fifthRequestIsRejectedWithoutCallingProviderAndSlotsRecover() throws Exception {
        var requests=new ArrayList<MvcResult>();
        try {
            for(int i=0;i<4;i++) requests.add(start("fixture-hold",false,false));
            await(() -> state().path("held").asInt()==4);
            int attempts=state().path("attempts").asInt();
            assertThat(finish(start("fixture-normal",false,false))).contains("RETRIEVAL_BUSY").doesNotContain("completed");
            assertThat(state().path("attempts").asInt()).isEqualTo(attempts);
        } finally { control.post().uri("/control/release").retrieve().toBodilessEntity(); }
        for(var request:requests) assertThat(finish(request)).contains("completed");
        assertThat(finish(start("fixture-normal",false,false))).contains("completed");
    }

    @Test void cancellationReleasesSlotWithoutSavingAndRecordsProviderConnectionState() throws Exception {
        // The simulation intermediary can retain its upstream socket; observe the origin directly.
        connect(System.getenv("ANSWER_STREAM_DIRECT_URL"));
        long beforeRooms=rooms.count(), beforeMessages=messages.count();
        var first=streams.stream("fixture-hold",principal.getUser())
                .filter(event -> "answer_delta".equals(event.data().type())).next().block(Duration.ofSeconds(4));
        assertThat(first).isNotNull();
        await(adapterCancelled::get);
        System.out.println("ANSWER_CANCEL_EVIDENCE adapterCancelled=true providerHeld="+state().path("held").asInt());
        control.post().uri("/control/release").retrieve().toBodilessEntity();
        assertThat(rooms.count()).isEqualTo(beforeRooms);
        assertThat(messages.count()).isEqualTo(beforeMessages);
        assertThat(finish(start("fixture-normal",false,false))).contains("completed");
    }

    @ParameterizedTest @ValueSource(strings={"429","503","invalid","empty","broken","truncated","stall"})
    void providerFailuresNeverCompleteOrSaveAndNextRequestRecovers(String failure) throws Exception {
        long beforeRooms=rooms.count(), beforeMessages=messages.count();
        String body=finish(start("fixture-"+failure,true,false));
        assertThat(body).contains("\"type\":\"error\"").doesNotContain("\"type\":\"completed\"","Synthetic provider");
        assertThat(rooms.count()).isEqualTo(beforeRooms);
        assertThat(messages.count()).isEqualTo(beforeMessages);
        assertThat(finish(start("fixture-normal",false,false))).contains("completed");
    }
}
