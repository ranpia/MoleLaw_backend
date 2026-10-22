package com.MoleLaw_backend.law.service.retrieval;
import com.MoleLaw_backend.law.dto.retrieval.EvidenceDecision;

import com.MoleLaw_backend.law.config.EvidenceChatClientConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit paid live check: fixed synthetic fixtures only, no DB, no collection tools, no automatic retry. */
@Tag("evidence-decision-live")
class EvidenceDecisionLiveTest {
    @Test
    void configuredModelProducesFourValidatedDecisionsAndActualUsage() throws Exception {
        String key = setting("OPENAI_API_KEY", "");
        assertFalse(key.isBlank(), "OPENAI_API_KEY must be configured");
        String modelName = setting("OPENAI_CHAT_MODEL", "gpt-4");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(60000);
        var api = OpenAiApi.builder().apiKey(key)
                .restClientBuilder(RestClient.builder().requestFactory(factory))
                .responseErrorHandler(new ResponseErrorHandler() {
                    @Override public boolean hasError(ClientHttpResponse response) throws IOException { return response.getStatusCode().isError(); }
                    @Override public void handleError(java.net.URI uri, org.springframework.http.HttpMethod method,
                                                      ClientHttpResponse response) throws IOException {
                        throw new IllegalStateException("Model provider HTTP " + response.getStatusCode().value());
                    }
                }).build();
        var model = OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(modelName).build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).noBackoff().build()).build();
        var client = new EvidenceChatClientConfig().evidenceCollectionChatClient(ChatClient.builder(model), 0.1, 1024,
                new ClassPathResource("prompts/evidence-decision.txt"));
        String format = new BeanOutputConverter<>(EvidenceDecision.class).getFormat();
        var scenarios = List.of(
                new Scenario(EvidenceDecision.Action.ANSWER_READY, List.of("fixture-1"),
                        "질문: 검증용 규칙상 10일 이내 신청해야 한다면 3일째 신청은 기한 이내인가? "
                                + "제공 근거 fixture-1: 검증용 가상 규칙이며 신청 기한은 사유 발생 후 10일 이내이다. 다른 쟁점은 없다."),
                new Scenario(EvidenceDecision.Action.SEARCH_AGAIN, List.of("fixture-2"),
                        "질문: 검증용 계약 규칙의 해제 통지 기한은? 제공 근거 fixture-2: 당사자는 서면으로 통지한다. "
                                + "서버 색인 현황: 해제 통지 기한 조문이 이미 색인에 존재한다. 현재 질의는 일반 통지였고 기한 조문을 아직 검색하지 않았다."),
                new Scenario(EvidenceDecision.Action.COLLECT_LAW, List.of(),
                        "질문: 민법상 계약 해제 후 원상회복 의무를 확인하고 싶다. 사실관계는 계약 해제 완료로 확정되었다. "
                                + "서버 상태: 제공 근거 없음. 민법은 아직 수집하지 않았고 관련 조문은 색인에 없다."),
                new Scenario(EvidenceDecision.Action.INSUFFICIENT, List.of(),
                        "질문: 이거 불법이야? 서버 상태: 행위·당사자·시점·계약 내용 모두 알려지지 않았다. 제공 근거 없음.")
        );
        int input = 0, output = 0, calls = 0;
        List<String> failures = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            String selected = System.getenv("EVIDENCE_DECISION_LIVE_ACTIONS");
            if (selected != null && !List.of(selected.split(",")).contains(scenario.expected().name())) continue;
            long started = System.nanoTime();
            org.springframework.ai.chat.model.ChatResponse response;
            try {
                response = client.prompt().messages(new org.springframework.ai.chat.messages.UserMessage(scenario.prompt() + "\n" + format))
                        .call().chatResponse();
            } catch (Exception failure) {
                // Drop causes and provider messages: they can contain credentials or request text.
                fail("Live model call failed: " + failure.getClass().getSimpleName());
                return;
            }
            assertNotNull(response);
            var usage = response.getMetadata().getUsage();
            assertNotNull(usage, "Live model usage must be available");
            assertNotNull(usage.getPromptTokens());
            assertNotNull(usage.getCompletionTokens());
            input += usage.getPromptTokens();
            output += usage.getCompletionTokens();
            calls++;
            System.out.printf("LIVE_USAGE model=%s scenario=%s inputTokens=%d outputTokens=%d elapsedMs=%d%n",
                    modelName, scenario.expected(), usage.getPromptTokens(), usage.getCompletionTokens(),
                    (System.nanoTime() - started) / 1_000_000);
            assertTrue(usage.getPromptTokens() > 0 && usage.getCompletionTokens() > 0, "Actual nonzero usage is required");
            EvidenceDecision decision;
            try {
                decision = EvidenceDecision.parse(response.getResult().getOutput().getText(), scenario.ids(), new ObjectMapper());
            } catch (RuntimeException invalid) {
                System.out.printf("LIVE_CONTRACT_REJECTED scenario=%s shape=%s%n", scenario.expected(),
                        shape(response.getResult().getOutput().getText()));
                failures.add("Strict contract rejected " + scenario.expected());
                continue;
            }
            System.out.printf("LIVE_DECISION model=%s expected=%s actual=%s inputTokens=%d outputTokens=%d elapsedMs=%d%n",
                    modelName, scenario.expected(), decision.action(), usage.getPromptTokens(), usage.getCompletionTokens(),
                    (System.nanoTime() - started) / 1_000_000);
            if (scenario.expected() != decision.action()) failures.add("Expected " + scenario.expected() + " received " + decision.action());
        }
        System.out.printf("LIVE_DECISION_TOTAL calls=%d inputTokens=%d outputTokens=%d totalTokens=%d%n", calls, input, output, input + output);
        assertTrue(calls > 0, "At least one live scenario is required");
        assertTrue(failures.isEmpty(), String.join("; ", failures));
    }

    private String shape(String json) {
        try {
            var node = new ObjectMapper().readTree(json);
            List<String> fields = new ArrayList<>();
            for (String field : List.of("action", "reason", "missingIssues", "evidenceIds", "searchQueries", "lawSearches")) {
                var value = node.path(field);
                fields.add(field + ":" + value.getNodeType() + (value.isArray() ? "(" + value.size() + ")" : ""));
            }
            return String.join(",", fields);
        } catch (Exception failure) { return "INVALID_JSON"; }
    }

    private record Scenario(EvidenceDecision.Action expected, List<String> ids, String prompt) { }

    private String setting(String name, String fallback) throws IOException {
        String value = System.getenv(name);
        if (value != null && !value.isBlank()) return value.strip();
        if (!Files.exists(Path.of(".env"))) return fallback;
        for (String line : Files.readAllLines(Path.of(".env"), StandardCharsets.UTF_8)) {
            String entry = line.strip();
            if (entry.startsWith("export ")) entry = entry.substring(7).strip();
            if (!entry.matches(java.util.regex.Pattern.quote(name) + "\\s*=.*")) continue;
            value = entry.substring(entry.indexOf('=') + 1).strip();
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            return value;
        }
        return fallback;
    }
}
