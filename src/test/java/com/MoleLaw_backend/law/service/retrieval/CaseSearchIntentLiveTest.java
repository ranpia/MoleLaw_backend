package com.MoleLaw_backend.law.service.retrieval;

import com.MoleLaw_backend.common.ai.config.ReasoningOptions;
import com.MoleLaw_backend.law.config.CaseSearchProperties;
import com.MoleLaw_backend.law.config.EvidenceChatClientConfig;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.law.dto.retrieval.CaseQueryPlan;
import com.MoleLaw_backend.law.dto.retrieval.CaseSearchScope;
import com.MoleLaw_backend.law.dto.tool.LegalToolContracts.Candidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in paid check: intent plus metadata selection, not a judgment identity finding or legal answer. */
@Tag("case-search-intent-live")
class CaseSearchIntentLiveTest {
    @Test void modelPreservesNamedCaseAndCanSelectRelevantCandidatesBeyondTheFirstFour() throws Exception {
        var mapper = new ObjectMapper();
        String modelName = setting("APP_AI_CASE_QUERY_MODEL", "gpt-5.4-mini");
        var options = ReasoningOptions.create(modelName, "low", 4096);
        var factory = new SimpleClientHttpRequestFactory(); factory.setConnectTimeout(5000); factory.setReadTimeout(90000);
        var api = OpenAiApi.builder().apiKey(setting("OPENAI_API_KEY", ""))
                .restClientBuilder(RestClient.builder().requestFactory(factory)).responseErrorHandler(new ResponseErrorHandler() {
                    @Override public boolean hasError(ClientHttpResponse response) throws IOException { return response.getStatusCode().isError(); }
                    @Override public void handleError(java.net.URI uri, org.springframework.http.HttpMethod method,
                            ClientHttpResponse response) throws IOException {
                        throw new IllegalStateException("Model provider HTTP " + response.getStatusCode().value());
                    }
                }).build();
        var delegate = OpenAiChatModel.builder().openAiApi(api).defaultOptions(options)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).noBackoff().build()).build();
        ChatModel measured = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                var response = delegate.call(prompt); var usage = response.getMetadata().getUsage();
                System.out.printf("CASE_INTENT_LIVE inputTokens=%d outputTokens=%d%n", usage.getPromptTokens(), usage.getCompletionTokens());
                return response;
            }
        };
        var configs = new EvidenceChatClientConfig();
        var queryClient = configs.caseQueryPlanningChatClient(ChatClient.builder(measured), modelName, "low", 2048);
        var law = new EvidenceItem("law", "LAW_CHUNK", "1:48", "건축법 제48조", "https://www.law.go.kr", "구조 안전 관련 규정");
        var routingClient = new com.MoleLaw_backend.chat.config.AnswerChatClientConfig().followUpCasePlanningChatClient(
                ChatClient.builder(measured), modelName, "low", 2048);
        var oldCase = new EvidenceItem("old-case", "CASE_BODY", "176175", "건축법위반", "https://www.law.go.kr",
                mapper.writeValueAsString(java.util.Map.of("사건번호", "2013노433", "판결요지", "옹벽 하중조건과 구조계산 검토 책임")));
        var routing = new com.MoleLaw_backend.chat.service.answer.FollowUpCasePlanner(routingClient, mapper)
                .plan("판례 새로 찾아 삼풍백화점의 판결", java.util.List.of(law, oldCase), "live-room");
        assertThat(routing.action()).isEqualTo(com.MoleLaw_backend.chat.dto.answer.FollowUpCasePlan.Action.SEARCH_CASES);
        assertThat(routing.query()).contains("삼풍");
        System.out.println("CASE_INTENT_LIVE followUpAction=SEARCH_CASES despiteExistingUnrelatedCase=true");
        var summary = new com.MoleLaw_backend.chat.service.answer.FollowUpCasePlanner(routingClient, mapper)
                .plan("정리해줘", java.util.List.of(law, oldCase), "live-room");
        assertThat(summary.action()).isEqualTo(com.MoleLaw_backend.chat.dto.answer.FollowUpCasePlan.Action.ANSWER_FROM_ROOM);
        System.out.println("CASE_INTENT_LIVE latestSummaryDoesNotReopenSearch=true");
        var plan = new CaseQueryPlanner(queryClient, mapper).plan("하중·구조안전 관련 판례", law,
                "삼풍백화점 사건을 직접 판단한 판결을 찾아줘", CaseSearchScope.LAW_REFERENCE);
        assertThat(plan.intent()).isEqualTo(CaseQueryPlan.Intent.SPECIFIC_CASE);
        assertThat(plan.targets()).anyMatch(target -> target.contains("삼풍"));
        var bridgePlan = new CaseQueryPlanner(queryClient, mapper).plan("성수대교 사건 직접 판례 대법원 사건번호 업무상과실치사상 공동정범",
                law, "네, 관련 판례를 검색해 주세요.", CaseSearchScope.BODY);
        assertThat(bridgePlan.intent()).isEqualTo(CaseQueryPlan.Intent.SPECIFIC_CASE);
        assertThat(bridgePlan.targets()).anyMatch(target -> target.contains("성수대교"));
        assertThat(bridgePlan.queries().get(0)).contains("성수대교");
        System.out.println("CASE_INTENT_LIVE consentOnlyTurnPreservesPriorNamedEvent=true structuredQueryAccepted=true");
        var candidates = Arrays.asList(mapper.readValue(new org.springframework.core.io.ClassPathResource("cases/named-case-candidates.json")
                .getInputStream(), Candidate[].class));
        var rerankClient = configs.caseRerankChatClient(ChatClient.builder(measured), modelName, "low", 4096);
        var selected = new CaseCandidateReranker(rerankClient, mapper, new CaseSearchProperties())
                .selectForReading(plan, law, candidates, 4);
        // The user did not request criminal proceedings specifically; the direct civil judgment also qualifies.
        assertThat(selected).containsAnyOf("114109", "79152");
        assertThat(selected).anyMatch(id -> candidates.subList(4, candidates.size()).stream().anyMatch(candidate -> candidate.id().equals(id)));
        System.out.printf("CASE_INTENT_LIVE intent=%s scope=%s catalog=%d chosen=%d laterCandidateSelected=true%n",
                plan.intent(), plan.scope(), candidates.size(), selected.size());
    }

    private String setting(String name, String fallback) throws IOException {
        for (String line : Files.exists(Path.of(".env")) ? Files.readAllLines(Path.of(".env")) : java.util.List.<String>of()) {
            String entry = line.strip(); if (!entry.startsWith(name + "=")) continue;
            String value = entry.substring(name.length() + 1).strip();
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))))
                value = value.substring(1, value.length() - 1);
            return value;
        }
        String value = System.getenv(name); return value == null || value.isBlank() ? fallback : value.strip();
    }
}
