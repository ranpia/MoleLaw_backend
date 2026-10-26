package com.MoleLaw_backend.law.service.retrieval;

import com.MoleLaw_backend.law.config.EvidenceChatClientConfig;
import com.MoleLaw_backend.law.dto.retrieval.QuestionRetrievalPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit one-call paid extraction check. No DB, legal API, embedding or raw question logging. */
@Tag("question-analysis-live")
class QuestionAnalysisLiveTest {
    @Test void compoundUnspacedQuestionRetainsBothRequestsAndActualUsage() throws Exception {
        String key = setting("OPENAI_API_KEY", "");
        assertThat(key).isNotBlank();
        String modelName = setting("APP_AI_QUESTION_MODEL", "gpt-5.4-mini");
        String effort = setting("APP_AI_QUESTION_REASONING_EFFORT", "low");
        int completionLimit = Integer.parseInt(setting("APP_AI_QUESTION_MAX_COMPLETION_TOKENS", "4096"));
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000); factory.setReadTimeout(90000);
        var api = OpenAiApi.builder().apiKey(key).restClientBuilder(RestClient.builder().requestFactory(factory))
                .responseErrorHandler(new ResponseErrorHandler() {
                    @Override public boolean hasError(ClientHttpResponse response) throws IOException { return response.getStatusCode().isError(); }
                    @Override public void handleError(java.net.URI uri, org.springframework.http.HttpMethod method,
                            ClientHttpResponse response) throws IOException {
                        var error = new ObjectMapper().readTree(response.getBody()).path("error");
                        throw new IllegalStateException("Model provider HTTP " + response.getStatusCode().value()
                                + " code=" + error.path("code").asText() + " param=" + error.path("param").asText());
                    }
                }).build();
        var model = OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(com.MoleLaw_backend.common.ai.config.ReasoningOptions.create(modelName, effort, completionLimit))
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).noBackoff().build()).build();
        var client = new EvidenceChatClientConfig().questionPlanningChatClient(ChatClient.builder(model), modelName,
                effort, completionLimit,
                new ClassPathResource("prompts/question-retrieval-plan.txt"));
        String question = "대형건축물이나다중이용업소가입점하는건물의리모델링을진행하려고합니다."
                + "건축사사무소를통해건축법상건축허가및신고절차를확인하고도면을작성했는데소방관서의동의및현장점검과정에서"
                + "건축법상기준과소방법상기준이충돌하거나추가시설설치를요구받았습니다."
                + "건축법에서는방화구획및피난계단설치기준을충족하여허가를받았으나소방법상특정소방대상물기준이적용되어"
                + "스프링클러및제연설비의추가설치가필요하다고합니다."
                + "1.건축법상건축허가를받았더라도소방법상소방시설기준이나소방본부장소방서장의동의를얻지못하면"
                + "실제건축공사나사용승인(준공)이불가능한지,두법률간의우선순위와협의절차가어떻게되는지"
                + "2.건축물용도변경이나증축시건축법상소급적용이되지않는구조라하더라도소방법상소방시설설치기준이강화된경우"
                + "기존건물에도소급적용되어추가공사를해야하는지법적근거가궁금합니다.";
        long started = System.nanoTime();
        var response = client.prompt().user(question).call().chatResponse();
        assertThat(response).isNotNull();
        var usage = response.getMetadata().getUsage();
        System.out.printf("QUESTION_ANALYSIS_LIVE model=%s inputTokens=%d outputTokens=%d elapsedMs=%d%n",
                modelName, usage.getPromptTokens(), usage.getCompletionTokens(), (System.nanoTime() - started) / 1_000_000);
        var plan = QuestionRetrievalPlan.parse(response.getResult().getOutput().getText(), new ObjectMapper());
        assertThat(plan.analysis()).isNotNull();
        plan.analysis().validateAgainst(question, false);
        assertThat(plan.analysis().issues()).hasSizeGreaterThanOrEqualTo(2);
        String requests = String.join(" ", plan.analysis().issues().stream().map(issue -> issue.request()).toList());
        assertThat(requests).contains("동의");
        assertThat(requests.contains("소급") || requests.contains("기존")).isTrue();
        assertThat(String.join(" ", plan.initialSearchQueries())).contains("소방");
        assertThat(plan.initialSearchQueries()).hasSizeGreaterThanOrEqualTo(2);
        System.out.printf("QUESTION_ANALYSIS_LIVE validatedIssues=%d queries=%d relations=%d%n",
                plan.analysis().issues().size(), plan.initialSearchQueries().size(), plan.analysis().relations().size());
    }

    private String setting(String name, String fallback) throws IOException {
        String value;
        // Match the Docker env_file, rather than inheriting the coding tool's provider credentials.
        for (String line : Files.exists(Path.of(".env")) ? Files.readAllLines(Path.of(".env")) : java.util.List.<String>of()) {
            String entry = line.strip();
            if (!entry.startsWith(name + "=")) continue;
            value = entry.substring(name.length() + 1).strip();
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1, value.length() - 1);
            return value;
        }
        value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
