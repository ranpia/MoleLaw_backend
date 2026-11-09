package com.MoleLaw_backend.controller;

import com.MoleLaw_backend.dto.response.ErrorResponse;
import com.MoleLaw_backend.util.exception.ErrorCode;
import com.MoleLaw_backend.util.exception.MolelawException;

import com.MoleLaw_backend.dto.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import java.io.IOException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ObjectMapper mapper;

    public GlobalExceptionHandler() {
        this(new ObjectMapper());
    }

    @Autowired
    public GlobalExceptionHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleException(Exception failure) {
        ErrorResponse error = describe(failure, ErrorCode.INTERNAL_SERVER_ERROR);
        HttpHeaders headers = new HttpHeaders();
        if (failure instanceof org.springframework.web.ErrorResponse framework) {
            headers.putAll(framework.getHeaders());
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return ResponseEntity.status(error.getStatus()).headers(headers).body(ApiResponse.error(error));
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException failure) throws IOException {
        writeError(response, failure, ErrorCode.UNAUTHORIZED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException failure) throws IOException {
        writeError(response, failure, ErrorCode.FORBIDDEN);
    }

    public void writeError(HttpServletResponse response, Throwable failure, ErrorCode fallback) throws IOException {
        ErrorResponse error = describe(failure, fallback);
        if (response.isCommitted()) return;
        response.setStatus(error.getStatus());
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(mapper.writeValueAsString(ApiResponse.error(error)));
    }

    /** Streams share classification without replacing committed HTTP headers. */
    public static ErrorResponse describe(Throwable failure, ErrorCode fallback) {
        while ((failure instanceof jakarta.servlet.ServletException
                || failure instanceof java.util.concurrent.CompletionException
                || failure instanceof java.util.concurrent.ExecutionException) && failure.getCause() != null) {
            failure = failure.getCause();
        }
        ErrorCode code = fallback;
        if (failure instanceof MolelawException business) {
            code = business.getErrorCode();
        } else if (failure instanceof AuthenticationException) {
            code = ErrorCode.UNAUTHORIZED;
        } else if (failure instanceof AccessDeniedException) {
            code = ErrorCode.FORBIDDEN;
        } else if (failure instanceof MethodArgumentNotValidException
                || failure instanceof ConstraintViolationException
                || failure instanceof HttpMessageNotReadableException
                || failure instanceof org.springframework.validation.BindException
                || failure instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException) {
            code = ErrorCode.INVALID_REQUEST;
        } else if (failure instanceof org.springframework.web.ErrorResponse framework) {
            code = switch (framework.getStatusCode().value()) {
                case 400 -> ErrorCode.INVALID_REQUEST;
                case 404 -> ErrorCode.NOT_FOUND;
                case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
                case 406 -> ErrorCode.NOT_ACCEPTABLE;
                case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
                case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
                case 503 -> ErrorCode.SERVICE_UNAVAILABLE;
                default -> fallback;
            };
        }
        logFailure(failure, code);
        return new ErrorResponse(code);
    }

    public static void logFailure(Throwable failure, ErrorCode code) {
        // Messages and causes may contain credentials, questions or provider bodies.
        String type = failure.getClass().getSimpleName();
        if (code.getStatus() >= 500) {
            Throwable cause = failure.getCause();
            log.error("API 처리 실패 code={} errorType={} causeType={} location={}", code, type,
                    cause == null ? "none" : cause.getClass().getSimpleName(),
                    failure.getStackTrace().length == 0 ? "unknown" : failure.getStackTrace()[0]);
        } else {
            log.warn("API 요청 거절 code={} errorType={}", code, type);
        }
    }
}
