package com.MoleLaw_backend.law.controller;

import com.MoleLaw_backend.common.config.TestRefreshTokenStoreConfig;
import com.MoleLaw_backend.law.dto.response.LawRetrievalComparisonResponse;
import com.MoleLaw_backend.law.dto.retrieval.RetrievalBackend;
import com.MoleLaw_backend.law.service.retrieval.LawRetrievalComparisonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestRefreshTokenStoreConfig.class, LawRetrievalComparisonControllerTest.ControllerFixture.class})
class LawRetrievalComparisonControllerTest {
    @TestConfiguration static class ControllerFixture {
        @Bean LawRetrievalComparisonController localComparison(LawRetrievalComparisonService service) {
            return new LawRetrievalComparisonController(service);
        }
    }
    @MockitoBean ChatModel model;
    @MockitoBean EmbeddingModel embeddings;
    @MockitoBean LawRetrievalComparisonService service;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test @WithMockUser void returnsProviderFailureAsDiagnosticStatusInsteadOfEmptyResults() throws Exception {
        when(service.searchAi(any())).thenReturn(new LawRetrievalComparisonResponse.BackendResult(RetrievalBackend.AI_SEARCH,
                LawRetrievalComparisonResponse.Status.UNAVAILABLE, 100, 0, null, null, "API_FAILURE", List.of()));
        mvc.perform(post("/api/local/law-retrieval/ai-search").with(csrf()).contentType("application/json")
                .content("{\"query\":\"공항\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.errorCode").value("API_FAILURE"));
    }

    @Test @WithMockUser void invalidBoundsAndCodePointLimitDoNotInvokeTools() throws Exception {
        for (String content : List.of("{\"query\":\"공항\",\"display\":16}", "{\"query\":\"공항\",\"page\":0}",
                "{\"query\":\" \"}", "{\"query\":\"" + "가".repeat(2001) + "\"}"))
            mvc.perform(post("/api/local/law-retrieval/ai-search").with(csrf()).contentType("application/json").content(content))
                    .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void anonymousCannotUseComparison() throws Exception {
        mvc.perform(post("/api/local/law-retrieval/compare").with(csrf()).contentType("application/json")
                .content("{\"query\":\"공항\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test @WithMockUser void csrfIsRequired() throws Exception {
        mvc.perform(post("/api/local/law-retrieval/compare").contentType("application/json")
                .content("{\"query\":\"공항\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test @WithMockUser void openApiDocumentsBothDiagnosticContractsAndSecurity() throws Exception {
        var spec = mapper.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        for (String path : List.of("ai-search", "compare")) {
            var operation = spec.path("paths").path("/api/local/law-retrieval/" + path).path("post");
            assertThat(operation.path("summary").asText()).isNotBlank();
            assertThat(operation.path("security").isEmpty()).isFalse();
            for (String code : List.of("200", "400", "401", "403")) assertThat(operation.path("responses").has(code)).isTrue();
            var request = operation.path("requestBody").path("content").path("application/json");
            assertThat(request.has("examples") || request.has("example")).isTrue();
            assertThat(operation.path("responses").path("200").path("content").path("application/json").has("schema")).isTrue();
        }
    }
}
