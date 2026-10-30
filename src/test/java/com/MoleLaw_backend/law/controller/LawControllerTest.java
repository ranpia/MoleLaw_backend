package com.MoleLaw_backend.law.controller;

import com.MoleLaw_backend.common.exception.GlobalExceptionHandler;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.chat.dto.response.LegalAnswerResponse;
import com.MoleLaw_backend.law.service.search.CaseSearchService;
import com.MoleLaw_backend.law.service.analysis.KeywordExtractionService;
import com.MoleLaw_backend.chat.service.answer.LegalAnswerService;
import com.MoleLaw_backend.law.service.search.LawSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LawControllerTest {
    private final LawSearchService laws = mock(LawSearchService.class);
    private final KeywordExtractionService keywords = mock(KeywordExtractionService.class);

    private MockMvc mvc;
    private static final String ROW = """
            {"법령명한글":"민법","소관부처명":"법무부","법령구분명":"법률",
             "공포번호":"00001","공포일자":"20260101","시행일자":"20260317"}
            """;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new LawController(laws, keywords))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void questionSearchUsesQueryAndRejectsTheOldContentFieldBeforeCallingModels() throws Exception {
        mvc.perform(post("/api/laws/search").contentType("application/json").content("{\"content\":\"민법 질문\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(keywords, laws);
        when(keywords.extractKeywords("민법 질문")).thenReturn(
                new com.MoleLaw_backend.law.dto.response.KeywordExtractionResponse(java.util.List.of("민법"), "민법 질문", "법무부"));
        when(laws.searchLawByKeyword(eq("민법"), anyString())).thenReturn("{\"LawSearch\":{\"totalCnt\":\"0\"}}");
        mvc.perform(post("/api/laws/search").contentType("application/json").content("{\"query\":\"민법 질문\"}"))
                .andExpect(status().isOk()).andExpect(content().string("{\"LawSearch\":{\"totalCnt\":\"0\"}}"));
        verify(keywords).extractKeywords("민법 질문");
    }

    @Test
    void lawSearchReturnsActualLawSearchFieldsInsteadOfEmptyArray() throws Exception {
        when(laws.searchLawByKeyword("민법")).thenReturn("{\"LawSearch\":{\"totalCnt\":\"1\",\"law\":[" + ROW + "]}}");
        mvc.perform(get("/api/laws").param("keyword", "민법"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].lawName").value("민법"))
                .andExpect(jsonPath("$[0].ministry").value("법무부"))
                .andExpect(jsonPath("$[0].lawType").value("법률"))
                .andExpect(jsonPath("$[0].proclamationNo").value("00001"))
                .andExpect(jsonPath("$[0].proclamationDate").value("20260101"))
                .andExpect(jsonPath("$[0].enforcementDate").value("20260317"));
    }

    @Test
    void singleObjectResponseAndMinistryParameterAreSupported() throws Exception {
        when(laws.searchLawByKeyword("민법", "1270000"))
                .thenReturn("{\"LawSearch\":{\"totalCnt\":1,\"law\":" + ROW + "}}");
        mvc.perform(get("/api/laws").param("keyword", "민법").param("orgCode", "1270000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].lawName").value("민법"));
        verify(laws).searchLawByKeyword("민법", "1270000");
    }

    @Test
    void normalZeroResultsRemainAnEmptyArray() throws Exception {
        when(laws.searchLawByKeyword("없음")).thenReturn("{\"LawSearch\":{\"totalCnt\":\"0\"}}");
        mvc.perform(get("/api/laws").param("keyword", "없음"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void malformedOrMissingRowsProduceDocumentedErrorInsteadOfEmptySuccess() throws Exception {
        for (String response : new String[]{"not-json", "{}", "null", "{\"LawSearch\":{\"totalCnt\":1}}",
                "{\"LawSearch\":{\"totalCnt\":1,\"law\":{}}}"}) {
            when(laws.searchLawByKeyword("민법")).thenReturn(response);
            mvc.perform(get("/api/laws").param("keyword", "민법"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("OPENLAW_INVALID_RESPONSE"));
        }
    }


}
