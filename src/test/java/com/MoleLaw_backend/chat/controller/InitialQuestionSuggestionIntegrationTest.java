package com.MoleLaw_backend.chat.controller;

import com.MoleLaw_backend.auth.security.CustomUserDetails;
import com.MoleLaw_backend.chat.repository.ChatRoomRepository;
import com.MoleLaw_backend.chat.repository.MessageRepository;
import com.MoleLaw_backend.common.config.TestRefreshTokenStoreConfig;
import com.MoleLaw_backend.law.repository.LegalSourceRepository;
import com.MoleLaw_backend.user.entity.User;
import java.util.List;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestRefreshTokenStoreConfig.class)
class InitialQuestionSuggestionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ChatRoomRepository rooms;
    @Autowired MessageRepository messages;
    @Autowired LegalSourceRepository sources;
    @MockitoBean ChatModel model;
    @MockitoBean EmbeddingModel embeddings;
    private final CustomUserDetails principal = new CustomUserDetails(User.builder().id(1L).email("suggestion@example.test").provider("local").nickname("사용자").build());
    private static final String PATH = "/api/legal-answers/suggestions";

    @Test void authenticatedDraftReturnsSuggestionsWithoutCreatingConsultationOrReadingHistory() throws Exception {
        long roomCount = rooms.count(), messageCount = messages.count(), sourceCount = sources.count();
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            assertThat(prompt.getInstructions()).hasSize(2);
            assertThat(((org.springframework.ai.model.tool.ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks()).isEmpty();
            return new ChatResponse(List.of(new Generation(new AssistantMessage("{\"suggestions\":[{\"label\":\"판단 기준\",\"focus\":\"위 상황에서 검토할 기준을 알려주세요.\"}]}"))));
        });
        mvc.perform(post(PATH).with(user(principal)).with(csrf()).contentType("application/json").content("{\"query\":\"책임이 궁금합니다.\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.suggestedQuestions[0].id").value("suggestion-1"))
                .andExpect(jsonPath("$.suggestedQuestions[0].question").value("책임이 궁금합니다.\n\n위 상황에서 검토할 기준을 알려주세요."))
                .andExpect(jsonPath("$.caseSearchRequested").doesNotExist());
        assertThat(rooms.count()).isEqualTo(roomCount);
        assertThat(messages.count()).isEqualTo(messageCount);
        assertThat(sources.count()).isEqualTo(sourceCount);
        verifyNoInteractions(embeddings);
    }

    @Test void inputAuthenticationAndCsrfFailuresDoNotCallModel() throws Exception {
        for (String body : List.of("{}", "{\"query\":\" \"}", "null", "{\"query\":\"" + "가".repeat(2001) + "\"}"))
            mvc.perform(post(PATH).with(user(principal)).with(csrf()).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        mvc.perform(post(PATH).with(csrf()).contentType("application/json").content("{\"query\":\"질문\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).with(user(principal)).contentType("application/json").content("{\"query\":\"질문\"}"))
                .andExpect(status().isForbidden());
        verify(model, never()).call(any(Prompt.class));
    }

    @Test void invalidModelOutputReturnsAnErrorInsteadOfStartingConsultation() throws Exception {
        long count = rooms.count();
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"suggestions\":[]}")))));
        mvc.perform(post(PATH).with(user(principal)).with(csrf()).contentType("application/json").content("{\"query\":\"질문\"}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("GPT_API_FAILURE"));
        assertThat(rooms.count()).isEqualTo(count);
    }
}
