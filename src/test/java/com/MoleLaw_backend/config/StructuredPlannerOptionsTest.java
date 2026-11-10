package com.MoleLaw_backend.config;

import com.MoleLaw_backend.config.AnswerChatClientConfig;
import com.MoleLaw_backend.service.answer.FollowUpCasePlanner;
import com.MoleLaw_backend.service.ai.AiPrompts;
import com.MoleLaw_backend.config.AiConfig;
import com.MoleLaw_backend.config.CaseSearchProperties;
import com.MoleLaw_backend.config.EvidenceChatClientConfig;
import com.MoleLaw_backend.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.dto.tool.LegalToolContracts.Candidate;
import com.MoleLaw_backend.service.retrieval.CaseCandidateReranker;
import com.MoleLaw_backend.service.retrieval.CaseQueryPlanner;
import com.MoleLaw_backend.service.retrieval.EvidenceReadPlanner;
import com.MoleLaw_backend.service.tool.EvidenceReadToolService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class StructuredPlannerOptionsTest {
    @Test
    void dynamicSchemasPreserveConfiguredRoleOptionsWithoutMutatingSharedDefaults() {
        var model = mock(ChatModel.class);
        var reader = mock(EvidenceReadToolService.class);
        var mapper = new ObjectMapper();
        var context = new ApplicationContextRunner()
                .withUserConfiguration(AiConfig.class, EvidenceChatClientConfig.class, AnswerChatClientConfig.class,
                        EvidenceReadPlanner.class, CaseQueryPlanner.class, CaseCandidateReranker.class, FollowUpCasePlanner.class)
                .withBean(ChatModel.class, () -> model)
                .withBean(ObjectMapper.class, () -> mapper)
                .withBean(CaseSearchProperties.class, CaseSearchProperties::new)
                .withBean(EvidenceReadToolService.class, () -> reader)
                .withBean("persistedChatMemory", ChatMemory.class, () -> mock(ChatMemory.class))
                .withBean(AiPrompts.class, () -> {
                    var prompts = mock(AiPrompts.class);
                    when(prompts.getLegalSystem()).thenReturn("법률 상담");
                    return prompts;
                })
                .withPropertyValues("app.ai.read.model=read-model", "app.ai.read.reasoning-effort=high",
                        "app.ai.read.max-completion-tokens=1234", "app.ai.case-query.model=query-model",
                        "app.ai.case-query.reasoning-effort=medium", "app.ai.case-query.max-completion-tokens=2345",
                        "app.ai.case-rerank.model=rerank-model", "app.ai.case-rerank.reasoning-effort=low",
                        "app.ai.case-rerank.max-completion-tokens=3456", "app.ai.follow-up-plan.model=route-model",
                        "app.ai.follow-up-plan.reasoning-effort=high", "app.ai.follow-up-plan.max-completion-tokens=4567");
        when(model.call(any(Prompt.class))).thenReturn(response("{\"evidenceIds\":[]}"),
                response("{\"evidenceIds\":[]}"),
                response("{\"queries\":[\"손해배상\"],\"intent\":\"SIMILAR_ISSUE\",\"targets\":[],\"scope\":\"BODY\"}"),
                response("{\"candidateIds\":[\"202\"]}"),
                response("{\"action\":\"ANSWER_FROM_ROOM\",\"lawEvidenceId\":null,\"query\":\"\",\"reason\":\"일반 설명\"}"));
        context.run(application -> {
            assertThat(application).hasNotFailed();
            var law = new EvidenceItem("law-1", "LAW_AI_SEARCH", "source-1", "법령", "https://www.law.go.kr", "본문");
            var otherLaw = new EvidenceItem("law-2", "LAW_AI_SEARCH", "source-2", "다른 법령", "https://www.law.go.kr", "본문");
            application.getBean(EvidenceReadPlanner.class).read("질문", List.of(law), null);
            application.getBean(EvidenceReadPlanner.class).read("질문", List.of(otherLaw), null);
            var plan = application.getBean(CaseQueryPlanner.class).planWithLawContexts("손해배상", List.of(), "판례 검색", null);
            application.getBean(CaseCandidateReranker.class).selectForReading(plan, null,
                    List.of(new Candidate("202", null, "사건명", null, "사건번호", "20260101", "법원")), 1);
            application.getBean(FollowUpCasePlanner.class).plan("설명해줘", List.of(law), "1:2");
            var prompts = ArgumentCaptor.forClass(Prompt.class);
            verify(model, times(5)).call(prompts.capture());
            var sent = prompts.getAllValues();
            assertOptions(sent.get(0), "read-model", "high", 1234);
            assertOptions(sent.get(1), "read-model", "high", 1234);
            assertOptions(sent.get(2), "query-model", "medium", 2345);
            assertOptions(sent.get(3), "rerank-model", "low", 3456);
            assertOptions(sent.get(4), "route-model", "high", 4567);
            String firstSchema = schema(sent.get(0));
            assertThat(firstSchema).contains("law-1").doesNotContain("law-2");
            assertThat(schema(sent.get(1))).contains("law-2").doesNotContain("law-1");
            assertThat(schema(sent.get(2))).doesNotContain("LAW_REFERENCE");
            assertThat(schema(sent.get(3))).contains("202");
            for (String name : List.of("evidenceReadOptions", "caseQueryPlanningOptions", "caseRerankOptions", "followUpCasePlanningOptions")) {
                assertThat(application.getBean(name, OpenAiChatOptions.class).getResponseFormat()).isNull();
            }
        });
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static void assertOptions(Prompt prompt, String model, String effort, int limit) {
        var options = (OpenAiChatOptions) prompt.getOptions();
        assertThat(options.getModel()).isEqualTo(model);
        assertThat(options.getReasoningEffort()).isEqualTo(effort);
        assertThat(options.getMaxCompletionTokens()).isEqualTo(limit);
        assertThat(options.getMaxTokens()).isNull();
        assertThat(options.getTemperature()).isNull();
        assertThat(options.getResponseFormat().getJsonSchema().getStrict()).isTrue();
    }

    private static String schema(Prompt prompt) {
        return ((OpenAiChatOptions) prompt.getOptions()).getResponseFormat().getJsonSchema().getSchema().toString();
    }
}
