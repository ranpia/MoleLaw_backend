package com.MoleLaw_backend.chat.controller;

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

class LegalAnswerControllerTest {
    private final LegalAnswerService answers = mock(LegalAnswerService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new LegalAnswerController(answers))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    @Test
    void answerRejectsMissingBlankAndOverlongQuestionsBeforeCallingModel() throws Exception {
        for (String body : new String[]{"{}", "{\"query\":null}", "{\"query\":\" \"}",
                "{\"query\":\"" + "가".repeat(2001) + "\"}"}) {
            mvc.perform(post("/api/legal-answers").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        verifyNoInteractions(answers);
    }

    @Test
    void answerReturnsStructuredRetrievalFailuresWithoutConvertingToGenericServerError() throws Exception {
        for (ErrorCode code : new ErrorCode[]{ErrorCode.EVIDENCE_INSUFFICIENT,
                ErrorCode.RETRIEVAL_UNAVAILABLE, ErrorCode.RETRIEVAL_BUSY}) {
            org.mockito.Mockito.doThrow(new MolelawException(code)).when(answers).getAnswer("법률 질문");
            mvc.perform(post("/api/legal-answers").contentType("application/json").content("{\"query\":\"법률 질문\"}"))
                    .andExpect(status().is(code.getStatus())).andExpect(jsonPath("$.error.code").value(code.name()));
        }
    }

    @Test
    void answerKeepsAnswerAndServerInfoResponseShape() throws Exception {
        when(answers.getAnswer("법률 질문")).thenReturn(new LegalAnswerResponse("인용 답변", "고정 근거"));
        mvc.perform(post("/api/legal-answers").contentType("application/json").content("{\"query\":\"법률 질문\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answer").value("인용 답변"))
                .andExpect(jsonPath("$.info").value("고정 근거"));
    }
}
