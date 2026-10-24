package com.MoleLaw_backend.common.exception;

import com.MoleLaw_backend.common.dto.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<?>> handleInvalidRequest(Exception ex) {
        // Do not expose rejected values or exception messages containing credentials.
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(new ErrorResponse(ErrorCode.INVALID_REQUEST)));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<?>> handleMissingResource(NoResourceFoundException ex) {
        ErrorResponse errorResponse = new ErrorResponse(ErrorCode.NOT_FOUND);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(ApiResponse.error(errorResponse));
    }

    @ExceptionHandler(MolelawException.class)
    public ResponseEntity<ApiResponse<?>> handleMolelawException(MolelawException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        ErrorResponse errorResponse = new ErrorResponse(errorCode, ex.getMessage());
        return ResponseEntity
                .status(errorCode.getStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(errorResponse));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleGeneralException(Exception ex) {
        ex.printStackTrace();
        System.out.println("🔥🔥🔥 Global exception handler 진입");

        ErrorResponse errorResponse = new ErrorResponse(ErrorCode.INTERNAL_SERVER_ERROR);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(errorResponse));
    }
}
