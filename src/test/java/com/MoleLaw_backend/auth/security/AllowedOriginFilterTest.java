package com.MoleLaw_backend.auth.security;

import com.MoleLaw_backend.common.exception.GlobalExceptionHandler;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AllowedOriginFilterTest {
    private final AllowedOriginFilter filter = new AllowedOriginFilter(
            List.of("http://localhost:3000"), new GlobalExceptionHandler());

    @Test
    void deniedOriginUsesTheGlobalErrorAndDoesNotRunTheChain() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/auth/logout");
        request.addHeader("Origin", "https://attacker.example");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { throw new AssertionError("must not run"); });
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("FORBIDDEN", "\"success\":false");
    }

    @Test
    void unexpectedFailureOutsideMvcIsSanitizedByTheGlobalHandler() throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/oauth2/test"), response,
                (req, res) -> { throw new ServletException(new IllegalStateException("private credential")); });
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains("INTERNAL_SERVER_ERROR").doesNotContain("private credential");
    }
}
