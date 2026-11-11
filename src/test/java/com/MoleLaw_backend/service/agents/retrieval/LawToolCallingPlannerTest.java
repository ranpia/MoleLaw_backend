package com.MoleLaw_backend.service.agents.retrieval;

import com.MoleLaw_backend.config.LawCandidateSelectionProperties;
import com.MoleLaw_backend.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.dto.retrieval.*;
import com.MoleLaw_backend.dto.tool.LegalToolContracts.*;
import com.MoleLaw_backend.dto.vector.LawSearchEvidence;
import com.MoleLaw_backend.service.agents.tool.*;
import com.MoleLaw_backend.util.exception.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.io.ClassPathResource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LawToolCallingPlannerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatModel model = mock(ChatModel.class);
    private final LawRagSearchToolService rag = mock(LawRagSearchToolService.class);
    private final LawVectorizationToolService vectors = mock(LawVectorizationToolService.class);
    private final LegalApiToolService laws = mock(LegalApiToolService.class);
    private final LawCandidateSelector selector = mock(LawCandidateSelector.class);
    private final EvidenceReadToolService reader = mock(EvidenceReadToolService.class);
    private final QuestionRetrievalPlan plan = new QuestionRetrievalPlan(QuestionRetrievalPlan.Type.LAW_EXPLANATION,
            QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE,List.of("질문"),List.of(),List.of());
    private final List<List<EvidenceItem>> confirmed = new ArrayList<>();
    private final RetrievalObserver observer = new RetrievalObserver() {
        @Override public void evidence(List<EvidenceItem> items) { confirmed.add(items); }
    };
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        when(rag.searchTop15(any(),anyString())).thenReturn(List.of(hit("본문")));
        when(reader.readEvidence(anyList(),anyList())).thenAnswer(call -> {
            List<EvidenceItem> evidence=call.getArgument(0);List<String> ids=call.getArgument(1);
            return evidence.stream().filter(item -> ids.contains(item.id())).toList();
        });
        when(selector.rank(anyString(),any(),any(),anyList())).thenAnswer(call -> ((List<Candidate>)call.getArgument(3)).stream()
                .map(c -> new LawCandidateSelection.Ranking(c.id(),95,"관련")).toList());
    }
    private LawSearchEvidence hit(String body) {
        return new LawSearchEvidence("123:1","source","123","001823","20260101","민법 제1조",body,body,
                "https://www.law.go.kr/lsInfoP.do?lsiSeq=123",.9,"revision","a".repeat(64),"generation");
    }
    private LawRetrievalToolSession session(boolean fixed) {
        var clock=Clock.systemUTC();
        return new LawRetrievalToolSession("질문",plan,new LegalToolContext(LegalToolContext.Purpose.FIRST_QUESTION,"trace",
                clock.instant().plus(Duration.ofMinutes(3)),128,clock),rag,vectors,laws,selector,reader,
                new LawCandidateSelectionProperties(),observer,fixed,64000,mapper);
    }
    private LawToolCallingPlanner planner(int steps,int inputLimit) throws Exception {
        var options=ToolCallingChatOptions.builder().internalToolExecutionEnabled(false).build();
        return new LawToolCallingPlanner(ChatClient.builder(model).defaultOptions(options).build(),mapper,steps,inputLimit,
                new ClassPathResource("prompts/law-retrieval-tools.txt"));
    }
    private ChatResponse tool(String name,String arguments) {
        return tools(List.of(new AssistantMessage.ToolCall(UUID.randomUUID().toString(),"function",name,arguments)));
    }
    private ChatResponse tools(List<AssistantMessage.ToolCall> calls) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("internal preparation text",Map.of(),calls))));
    }
    private ChatResponse text(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
    private com.fasterxml.jackson.databind.JsonNode result(Prompt prompt) throws Exception {
        var messages=prompt.getInstructions();
        var last=(ToolResponseMessage)messages.get(messages.size()-1);
        return mapper.readTree(last.getResponses().get(0).responseData());
    }
    private ChatResponse ready(String id) {
        return text("{\"action\":\"ANSWER_READY\",\"reason\":\"본문 확인\",\"missingIssues\":[],\"evidenceIds\":[\""+id+"\"],\"searchQueries\":[],\"lawSearches\":[]}");
    }
    private ChatResponse insufficient() {
        return text("{\"action\":\"INSUFFICIENT\",\"reason\":\"자료 부족\",\"missingIssues\":[\"본문\"],\"evidenceIds\":[],\"searchQueries\":[],\"lawSearches\":[]}");
    }

    @Test void actualSpringToolCallbacksSearchReadAndFinishWithoutLeakingPreparationText() throws Exception {
        var step=new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt=call.getArgument(0);
            var options=(ToolCallingChatOptions)prompt.getOptions();
            assertThat(options.getInternalToolExecutionEnabled()).isFalse();
            assertThat(options.getToolCallbacks()).extracting(c -> c.getToolDefinition().name())
                    .containsExactlyInAnyOrder("searchLawArticles","searchLaws","collectLaw","readLawArticles");
            return switch(step.getAndIncrement()) {
                case 0 -> tool("searchLawArticles","{\"searchQueries\":[\"질문\"]}");
                case 1 -> tool("readLawArticles","{\"evidenceIds\":[\""+result(prompt).path("candidates").get(0).path("id").asText()+"\"]}");
                default -> ready(result(prompt).path("evidence").get(0).path("id").asText());
            };
        });
        var collected=planner(12,32000).collect("질문",plan,session(false),observer);
        assertThat(collected.evidence()).hasSize(1);
        assertThat(confirmed).allSatisfy(list -> assertThat(list).allSatisfy(item -> assertThat(item.content()).isEqualTo("본문")));
        verify(model,times(3)).call(any(Prompt.class));verifyNoInteractions(laws,vectors);
    }
    @Test void emptyIndexTriggersModelChosenDiscoveryCollectionAndASecondSearch() throws Exception {
        when(rag.searchTop15(any(),anyString())).thenReturn(List.of(),List.of(hit("수집한 조문")));
        when(laws.searchLaws(any(),any())).thenReturn(new SearchResult(Status.AVAILABLE,
                List.of(new Candidate("123","001823","민법","20260101",null,null,null)),1,20,1,false,Instant.now(),"https://www.law.go.kr"));
        when(laws.getLawDetail(any(),eq("123"))).thenReturn(new DetailResult("source","123","001823","20260101","민법",1,true,
                Instant.now(),"https://www.law.go.kr",List.of("1")));
        var step=new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(call -> switch(step.getAndIncrement()) {
            case 0 -> tool("searchLawArticles","{\"searchQueries\":[\"질문\"]}");
            case 1 -> tool("searchLaws","{\"query\":\"민법\",\"field\":\"TITLE\",\"exactName\":true,\"ministry\":null}");
            case 2 -> tool("collectLaw","{\"candidateId\":\"123\"}");
            case 3 -> tool("searchLawArticles","{\"searchQueries\":[\"질문\"]}");
            case 4 -> tool("readLawArticles","{\"evidenceIds\":[\""+result(call.getArgument(0)).path("candidates").get(0).path("id").asText()+"\"]}");
            default -> ready(result(call.getArgument(0)).path("evidence").get(0).path("id").asText());
        });
        assertThat(planner(12,32000).collect("질문",plan,session(false),observer).evidence()).hasSize(1);
        verify(vectors).vectorizeLaw(any(),eq("source"));verify(rag,times(2)).searchTop15(any(),eq("질문"));
    }
    @Test void unseenOrUnreadEvidenceCannotBeUsedInTheFinalDecision() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(ready("invented"));
        assertThatThrownBy(() -> planner(12,32000).collect("질문",plan,session(false),observer))
                .isInstanceOf(GptApiException.class);
        verifyNoInteractions(rag,reader,laws,vectors);
    }
    @Test void anUnknownToolInABatchRejectsTheEntireBatchBeforeAnyAction() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(tools(List.of(
                new AssistantMessage.ToolCall("a","function","searchLawArticles","{\"searchQueries\":[\"질문\"]}"),
                new AssistantMessage.ToolCall("b","function","runSql","{}"))));
        assertThatThrownBy(() -> planner(12,32000).collect("질문",plan,session(false),observer)).isInstanceOf(GptApiException.class);
        verifyNoInteractions(rag,reader,laws,vectors);
    }
    @Test void fixedIndexOnlyDoesNotRegisterExternalSearchOrCollectionCallbacks() throws Exception {
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            var options=(ToolCallingChatOptions)call.<Prompt>getArgument(0).getOptions();
            assertThat(options.getToolCallbacks()).extracting(c -> c.getToolDefinition().name())
                    .containsExactlyInAnyOrder("searchLawArticles","readLawArticles");
            return tool("collectLaw","{\"candidateId\":\"123\"}");
        });
        assertThatThrownBy(() -> planner(12,32000).collect("질문",plan,session(true),observer)).isInstanceOf(GptApiException.class);
        verifyNoInteractions(laws,vectors);
    }
    @Test void repeatedToolChoicesStopAtTheModelStepLimitBeforeADeadEndWrite() throws Exception {
        when(model.call(any(Prompt.class))).thenAnswer(call -> tool("searchLawArticles","{\"searchQueries\":[\"질문\"]}"));
        assertThatThrownBy(() -> planner(2,32000).collect("질문",plan,session(false),observer))
                .isInstanceOfSatisfying(MolelawException.class,e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EVIDENCE_INSUFFICIENT));
        verify(model,times(2)).call(any(Prompt.class));verify(rag,times(1)).searchTop15(any(),anyString());
    }
    @Test void aPrematureInsufficientDecisionGetsOneBoundedInstructionToActuallySearch() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(insufficient(),insufficient());
        assertThatThrownBy(() -> planner(12,32000).collect("질문",plan,session(false),observer)).isInstanceOf(MolelawException.class);
        verify(model,times(2)).call(any(Prompt.class));
    }
    @Test void providerFailureIsNotRetried() throws Exception {
        var failure=new IllegalStateException("private provider response");
        when(model.call(any(Prompt.class))).thenThrow(failure);
        assertThatThrownBy(() -> planner(12,32000).collect("질문",plan,session(false),observer)).isSameAs(failure);
        verify(model,times(1)).call(any(Prompt.class));verifyNoInteractions(rag,laws,vectors,reader);
    }
    @Test void toolOptionsPreserveTheRoleModelReasoningAndCompletionBudget() throws Exception {
        var roleOptions=org.springframework.ai.openai.OpenAiChatOptions.builder().model("role-model")
                .reasoningEffort("medium").maxCompletionTokens(1234).internalToolExecutionEnabled(false).build();
        var client=ChatClient.builder(model).defaultOptions(roleOptions).build();
        var planner=new LawToolCallingPlanner(client,mapper,12,32000,new ClassPathResource("prompts/law-retrieval-tools.txt"));
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            var options=(org.springframework.ai.openai.OpenAiChatOptions)call.<Prompt>getArgument(0).getOptions();
            assertThat(options.getModel()).isEqualTo("role-model");
            assertThat(options.getReasoningEffort()).isEqualTo("medium");
            assertThat(options.getMaxCompletionTokens()).isEqualTo(1234);
            assertThat(options.getInternalToolExecutionEnabled()).isFalse();
            return insufficient();
        });
        assertThatThrownBy(() -> planner.collect("질문",plan,session(false),observer)).isInstanceOf(MolelawException.class);
        verify(model,times(2)).call(any(Prompt.class));
    }
    @Test void callbackWrappedBusyFailureKeepsItsDomainReason() throws Exception {
        when(rag.searchTop15(any(),anyString())).thenThrow(new LegalToolException(LegalToolException.Reason.BUSY,true));
        when(model.call(any(Prompt.class))).thenReturn(tool("searchLawArticles","{\"searchQueries\":[\"질문\"]}"));
        assertThatThrownBy(() -> planner(12,32000).collect("질문",plan,session(false),observer))
                .isInstanceOfSatisfying(LegalToolException.class,e -> assertThat(e.getReason()).isEqualTo(LegalToolException.Reason.BUSY));
        verify(model,times(1)).call(any(Prompt.class));
    }
    @Test void toolResponseBodiesAreCountedInTheNextModelInputBudget() throws Exception {
        when(rag.searchTop15(any(),anyString())).thenReturn(List.of(hit("source body ".repeat(5000))));
        var step=new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(call -> step.getAndIncrement()==0
                ? tool("searchLawArticles","{\"searchQueries\":[\"질문\"]}")
                : tool("readLawArticles","{\"evidenceIds\":[\""+result(call.getArgument(0)).path("candidates").get(0).path("id").asText()+"\"]}"));
        assertThatThrownBy(() -> planner(12,5000).collect("질문",plan,session(false),observer)).isInstanceOf(MolelawException.class);
        verify(model,times(2)).call(any(Prompt.class));
    }
    @Test void cancellationBeforeTheNextToolPreventsApiOrIndexSideEffects() throws Exception {
        var cancelled=new java.util.concurrent.CancellationException();
        var checks=new AtomicInteger();
        RetrievalObserver cancelling=new RetrievalObserver() {
            @Override public void progress(String stage) { if(checks.incrementAndGet()>1) throw cancelled; }
        };
        when(model.call(any(Prompt.class))).thenReturn(tool("searchLawArticles","{\"searchQueries\":[\"질문\"]}"));
        assertThatThrownBy(() -> planner(12,32000).collect("질문",plan,session(false),cancelling)).isSameAs(cancelled);
        verifyNoInteractions(rag,laws,vectors,reader);
    }
    @Test void cancellationWrappedByASpringToolCallbackIsNotReclassifiedAsAProviderFailure() throws Exception {
        var cancelled=new java.util.concurrent.CancellationException();
        when(rag.searchTop15(any(),anyString())).thenThrow(cancelled);
        when(model.call(any(Prompt.class))).thenReturn(tool("searchLawArticles","{\"searchQueries\":[\"질문\"]}"));
        assertThatThrownBy(() -> planner(12,32000).collect("질문",plan,session(false),observer)).isSameAs(cancelled);
        verify(model,times(1)).call(any(Prompt.class));verifyNoInteractions(laws,vectors,reader);
    }
}
