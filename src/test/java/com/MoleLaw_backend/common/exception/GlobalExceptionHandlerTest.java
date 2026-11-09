package com.MoleLaw_backend.common.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler errors = new GlobalExceptionHandler();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ErrorController())
            .setControllerAdvice(errors).build();

    @Test
    void malformedJsonAndValidationFailuresReturnSanitized400() throws Exception {
        for (String body : new String[]{"{", "{\"name\":\"\"}"}) {
            mvc.perform(post("/test/errors").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("success").value(false))
                    .andExpect(jsonPath("error.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void typeMismatchReturns400AndMissingResourceReturns404() throws Exception {
        mvc.perform(get("/test/errors/number/not-a-number"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("error.code").value("INVALID_REQUEST"));
        var missing = errors.handleException(new org.springframework.web.servlet.resource.NoResourceFoundException(
                org.springframework.http.HttpMethod.GET, "missing"));
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(missing.getBody().getError().getCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    void methodAndMediaTypeErrorsKeepTheirHttpStatusAndHeaders() throws Exception {
        mvc.perform(post("/test/errors/business"))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", "GET"))
                .andExpect(jsonPath("error.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(post("/test/errors").contentType(MediaType.TEXT_PLAIN).content("private input"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("error.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void businessCodeAndMessageArePreserved() throws Exception {
        mvc.perform(get("/test/errors/business"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("error.code").value("FORBIDDEN"));
        var failure = new MolelawException(ErrorCode.INVALID_REQUEST, "입력 조건", new IllegalStateException());
        assertThat(failure.getMessage()).isEqualTo("잘못된 요청입니다. - 입력 조건");
        assertThat(failure.getCause()).isInstanceOf(IllegalStateException.class);
        assertThat(new MolelawException(ErrorCode.INVALID_REQUEST, (String) null).getMessage())
                .isEqualTo(ErrorCode.INVALID_REQUEST.getMessage());
    }

    @Test
    void unexpectedFailureDoesNotExposeExceptionMessagesInResponseOrLogs(CapturedOutput output) throws Exception {
        mvc.perform(get("/test/errors/bug")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("error.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("error.message").value(ErrorCode.INTERNAL_SERVER_ERROR.getMessage()));
        assertThat(output.getAll()).contains("API 처리 실패", "IllegalStateException")
                .doesNotContain("secret-provider-value");
    }

    @Test
    void committedStreamKeepsItsStatusAndBodyAndUsesSharedClassification() throws Exception {
        var response = new MockHttpServletResponse();
        response.setContentType("application/x-ndjson");
        response.getWriter().write("existing stream");
        response.flushBuffer();
        errors.writeError(response, new IllegalStateException(), ErrorCode.INTERNAL_SERVER_ERROR);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("existing stream");
        assertThat(GlobalExceptionHandler.describe(new MolelawException(ErrorCode.RETRIEVAL_BUSY),
                ErrorCode.GPT_API_FAILURE).getCode()).isEqualTo("RETRIEVAL_BUSY");
    }

    @RestController
    @RequestMapping("/test/errors")
    static class ErrorController {
        @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
        String validate(@Valid @RequestBody Input input) { return input.name(); }

        @GetMapping("/number/{value}")
        String number(@PathVariable int value) { return Integer.toString(value); }

        @GetMapping("/{mode}")
        String fail(@PathVariable String mode) {
            if ("business".equals(mode)) throw new MolelawException(ErrorCode.FORBIDDEN);
            throw new IllegalStateException("secret-provider-value");
        }
    }

    record Input(@NotBlank String name) { }
}
