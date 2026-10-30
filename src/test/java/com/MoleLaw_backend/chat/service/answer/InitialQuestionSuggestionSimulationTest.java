package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.chat.config.AnswerChatClientConfig;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;

@Tag("postman-simulation")
@EnabledIfEnvironmentVariable(named = "QUESTION_SUGGESTION_SIMULATION_URL", matches = "http://127\\.0\\.0\\.1:[0-9]+")
class InitialQuestionSuggestionSimulationTest {
    @Test void realProviderHttpContractReturnsQuestionsAndRejectsInvalidContentAndOutage() throws Exception {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000); factory.setReadTimeout(5000);
        var api = OpenAiApi.builder().apiKey("synthetic-key")
                .baseUrl(System.getenv("QUESTION_SUGGESTION_SIMULATION_URL") + "/openai")
                .restClientBuilder(RestClient.builder().requestFactory(factory)).build();
        var model = OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(com.MoleLaw_backend.common.ai.config.ReasoningOptions.create("gpt-4o", "none", 8192))
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).noBackoff().build()).build();
        var client = new AnswerChatClientConfig().initialQuestionSuggestionChatClient(ChatClient.builder(model),
                "gpt-5.4-mini", "low", 2048, new ClassPathResource("prompts/initial-question-suggestions.txt"));
        var service = new InitialQuestionSuggestionService(client, new ObjectMapper());
        assertThat(service.suggest("fixture-case").suggestedQuestions()).singleElement()
                .satisfies(question -> assertThat(question.question()).isEqualTo("fixture-case\n\n위 상황에서 검토할 기준을 설명해 주세요."));
        assertThatThrownBy(() -> service.suggest("fixture-invalid")).isInstanceOf(GptApiException.class);
        assertThatThrownBy(() -> service.suggest("fixture-unavailable")).isInstanceOf(GptApiException.class);
    }
}
