package com.MoleLaw_backend.law.controller;

import com.MoleLaw_backend.common.exception.GlobalExceptionHandler;
import com.MoleLaw_backend.law.service.CaseSearchService;
import com.MoleLaw_backend.law.service.ExtractKeyword;
import com.MoleLaw_backend.chat.service.answer.LegalAnswerService;
import com.MoleLaw_backend.law.service.LawEmbeddingService;
import com.MoleLaw_backend.law.service.LawSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TestControllerTest {
    private final LawSearchService laws = mock(LawSearchService.class);
    private MockMvc mvc;
    private static final String ROW = """
            {"법령명한글":"민법","소관부처명":"법무부","법령구분명":"법률",
             "공포번호":"00001","공포일자":"20260101","시행일자":"20260317"}
            """;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TestController(laws, mock(ExtractKeyword.class),
                        mock(LegalAnswerService.class), mock(CaseSearchService.class), mock(LawEmbeddingService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void lawSearchReturnsActualLawSearchFieldsInsteadOfEmptyArray() throws Exception {
        when(laws.searchLawByKeyword("민법")).thenReturn("{\"LawSearch\":{\"totalCnt\":\"1\",\"law\":[" + ROW + "]}}");
        mvc.perform(get("/api/test/lawsearch").param("keyword", "민법"))
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
        mvc.perform(get("/api/test/lawsearch").param("keyword", "민법").param("orgCode", "1270000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].lawName").value("민법"));
        verify(laws).searchLawByKeyword("민법", "1270000");
    }

    @Test
    void normalZeroResultsRemainAnEmptyArray() throws Exception {
        when(laws.searchLawByKeyword("없음")).thenReturn("{\"LawSearch\":{\"totalCnt\":\"0\"}}");
        mvc.perform(get("/api/test/lawsearch").param("keyword", "없음"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void malformedOrMissingRowsProduceDocumentedErrorInsteadOfEmptySuccess() throws Exception {
        for (String response : new String[]{"not-json", "{}", "null", "{\"LawSearch\":{\"totalCnt\":1}}",
                "{\"LawSearch\":{\"totalCnt\":1,\"law\":{}}}"}) {
            when(laws.searchLawByKeyword("민법")).thenReturn(response);
            mvc.perform(get("/api/test/lawsearch").param("keyword", "민법"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("OPENLAW_INVALID_RESPONSE"));
        }
    }
}
