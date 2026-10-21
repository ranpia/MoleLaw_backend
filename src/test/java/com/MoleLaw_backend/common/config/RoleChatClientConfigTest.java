package com.MoleLaw_backend.common.config;

import com.MoleLaw_backend.chat.config.AnswerChatClientConfig;
import com.MoleLaw_backend.common.ai.config.AiConfig;
import com.MoleLaw_backend.common.ai.AiPrompts;
import com.MoleLaw_backend.law.config.EvidenceChatClientConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RoleChatClientConfigTest {
    private final ChatModel model = mock(ChatModel.class);
    private final ChatMemory memory = mock(ChatMemory.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(AiConfig.class, EvidenceChatClientConfig.class, AnswerChatClientConfig.class)
            .withBean("persistedChatMemory", ChatMemory.class, () -> memory)
            .withBean(AiPrompts.class, () -> {
                var prompts = mock(AiPrompts.class);
                when(prompts.getLegalSystem()).thenReturn("공통 법률 상담 지침");
                return prompts;
            })
            .withBean(ChatModel.class, () -> model);

    @Test
    void clientsShareModelButKeepTheirRolePromptsAndOptionsIsolated() {
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
        context.withPropertyValues("app.ai.evidence.max-tokens=512", "app.ai.answer.temperature=0.6").run(application -> {
            assertThat(application).hasNotFailed();
            verify(model, never()).call(any(Prompt.class));
            ChatClient evidence = application.getBean("evidenceCollectionChatClient", ChatClient.class);
            ChatClient answer = application.getBean("legalAnswerChatClient", ChatClient.class);
            ChatClient followUp = application.getBean("followUpAnswerChatClient", ChatClient.class);
            ChatClient legacy = application.getBean("chatClient", ChatClient.class);
            assertThat(application.getBean(ChatClient.class)).isSameAs(legacy);
            assertThat(evidence).isNotSameAs(answer);
            evidence.prompt().user("검토할 근거").call().content();
            answer.prompt().user("확정한 근거").advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "1:2")).call().content();
            verifyNoInteractions(memory);
            when(memory.get("1:2")).thenReturn(List.of());
            followUp.prompt().user("후속 질문").advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "1:2")).call().content();
            verify(memory).get("1:2");
            legacy.prompt().user("기존 호출").call().content();
            ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
            verify(model, times(4)).call(prompts.capture());
            var captured = prompts.getAllValues();
            assertThat(captured.get(0).getSystemMessage().getText()).contains("근거 수집과 검토").doesNotContain("답변을 작성합니다");
            assertThat(captured.get(0).getOptions().getTemperature()).isEqualTo(0.1);
            assertThat(captured.get(0).getOptions().getMaxTokens()).isEqualTo(512);
            assertThat(captured.get(1).getSystemMessage().getText()).contains("확정한 근거로 법률 상담 답변");
            assertThat(captured.get(1).getOptions().getTemperature()).isEqualTo(0.6);
            assertThat(captured.get(1).getOptions().getMaxTokens()).isEqualTo(4096);
            assertThat(captured.get(1).getSystemMessage().getText()).contains("공통 법률 상담 지침");
            assertThat(captured.get(2).getSystemMessage().getText()).isEqualTo(captured.get(1).getSystemMessage().getText());
            assertThat(captured.get(3).getInstructions()).hasSize(1);
            assertThat(application.getBean("baseAiChatClientBuilder"))
                    .isNotSameAs(application.getBean("baseAiChatClientBuilder"));
        });
    }

    @Test
    void invalidRoleConfigurationFailsBeforeAnyModelCall() {
        context.withPropertyValues("app.ai.evidence.max-tokens=0").run(application -> {
            assertThat(application).hasFailed();
            verify(model, never()).call(any(Prompt.class));
        });
    }
}
