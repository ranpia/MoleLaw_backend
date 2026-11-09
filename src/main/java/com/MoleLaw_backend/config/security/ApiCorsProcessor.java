package com.MoleLaw_backend.config.security;

import com.MoleLaw_backend.controller.GlobalExceptionHandler;
import com.MoleLaw_backend.util.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.DefaultCorsProcessor;

import java.io.IOException;

/** Adapts CORS rejection to the common API error boundary. */
@Component
@RequiredArgsConstructor
public class ApiCorsProcessor extends DefaultCorsProcessor {
    private final GlobalExceptionHandler errors;

    @Override
    protected void rejectRequest(ServerHttpResponse response) throws IOException {
        var servletResponse = (ServletServerHttpResponse) response;
        errors.writeError(servletResponse.getServletResponse(),
                new AccessDeniedException("CORS rejected"), ErrorCode.FORBIDDEN);
    }
}
