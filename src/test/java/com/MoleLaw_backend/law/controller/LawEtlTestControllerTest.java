package com.MoleLaw_backend.law.controller;

import com.MoleLaw_backend.common.config.TestRefreshTokenStoreConfig;
import com.MoleLaw_backend.law.service.etl.LawEtlTestService;
import com.MoleLaw_backend.law.dto.response.LawEtlTestResponse;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestRefreshTokenStoreConfig.class, LawEtlTestControllerTest.LocalControllerFixture.class})
class LawEtlTestControllerTest {
    // Explicit fixture exposes only this controller for contract/security tests; normal test/prod discovery excludes it.
    @TestConfiguration
    static class LocalControllerFixture {
        @Bean LawEtlTestController localEtlController(LawEtlTestService service) {
            return new LawEtlTestController(service);
        }
    }
    @MockitoBean ChatModel chatModel;
    @MockitoBean EmbeddingModel embeddingModel;
    @MockitoBean LawEtlTestService service;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test @WithMockUser
    void validIndexRequestUsesServiceAndInvalidLimitsNeverDo() throws Exception {
        when(service.collect(any())).thenReturn(new LawEtlTestResponse("run", 1, null, List.of()));
        mvc.perform(post("/api/local/law-etl/index").with(csrf()).contentType("application/json")
                .content("{\"query\":\"민법\",\"startPage\":1,\"maxPages\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.executionId").value("run"));
        clearInvocations(service);
        mvc.perform(post("/api/local/law-etl/index").with(csrf()).contentType("application/json")
                .content("{\"query\":\"민법\",\"startPage\":1,\"maxPages\":4}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/local/law-etl/reindex").with(csrf()).contentType("application/json")
                .content("{\"referenceId\":\"not-a-uuid\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test @WithMockUser
    void reindexAndSearchUseTheirOwnContracts() throws Exception {
        String id = "11111111-1111-1111-1111-111111111111";
        when(service.reindex(id)).thenReturn(new com.MoleLaw_backend.law.dto.vector.LawIndexResult(id, "collection", 1, 2));
        mvc.perform(post("/api/local/law-etl/reindex").with(csrf()).contentType("application/json")
                .content("{\"referenceId\":\"" + id + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.chunkCount").value(2));
        when(service.search("신의성실")).thenReturn(List.of(new com.MoleLaw_backend.law.dto.vector.LawSearchEvidence(
                "123:1", id, "123", "1", "20260101", "민법 제1조", "청크", "전체 조문",
                "https://www.law.go.kr/법령", 0.8, "revision", "fingerprint", "generation")));
        mvc.perform(post("/api/local/law-etl/search").with(csrf()).contentType("application/json")
                .content("{\"query\":\"신의성실\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].articleText").value("전체 조문"))
                .andExpect(jsonPath("$[0].generation").value("generation"));
        verify(service).reindex(id);
        verify(service).search("신의성실");
    }

    @Test @WithMockUser
    void csrfIsRequired() throws Exception {
        mvc.perform(post("/api/local/law-etl/search").contentType("application/json")
                .content("{\"query\":\"민법\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void anonymousCannotInvokeLocalTools() throws Exception {
        mvc.perform(post("/api/local/law-etl/search").with(csrf()).contentType("application/json")
                .content("{\"query\":\"민법\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test @WithMockUser
    void openApiIncludesSchemasExamplesAndSecurityForAllThreeOperations() throws Exception {
        var spec = mapper.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        for (String suffix : List.of("index", "reindex", "search")) {
            var operation = spec.path("paths").path("/api/local/law-etl/" + suffix).path("post");
            assertThat(operation.path("summary").asText()).isNotBlank();
            assertThat(operation.path("tags").toString()).contains("법령 ETL 테스트");
            assertThat(operation.path("security").isEmpty()).isFalse();
            var body = operation.path("requestBody").path("content").path("application/json");
            assertThat(body.has("examples") || body.has("example")).isTrue();
            assertThat(operation.path("responses").path("200").path("content").path("application/json").has("schema")).isTrue();
            for (String status : List.of("400", "401", "403", "503")) {
                assertThat(operation.path("responses").has(status)).isTrue();
            }
        }
        var indexExamples = spec.path("paths").path("/api/local/law-etl/index").path("post")
                .path("requestBody").path("content").path("application/json").path("examples");
        assertThat(indexExamples.has("검색 조건 지정")).isTrue();
        var searchExamples = spec.path("paths").path("/api/local/law-etl/search").path("post")
                .path("responses").path("200").path("content").path("application/json").path("examples");
        assertThat(searchExamples.has("검색 성공")).isTrue();
        assertThat(searchExamples.has("검색 결과 없음")).isTrue();
    }
}
