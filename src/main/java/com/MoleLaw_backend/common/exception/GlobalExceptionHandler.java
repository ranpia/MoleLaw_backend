package com.MoleLaw_backend.common.exception;

import com.MoleLaw_backend.common.dto.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<?>> handleMissingResource(NoResourceFoundException ex) {
        ErrorResponse errorResponse = new ErrorResponse(ErrorCode.NOT_FOUND);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(errorResponse));
    }

    @ExceptionHandler(MolelawException.class)
    public ResponseEntity<ApiResponse<?>> handleMolelawException(MolelawException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        ErrorResponse errorResponse = new ErrorResponse(errorCode, ex.getMessage());
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.error(errorResponse));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleGeneralException(Exception ex) {
        ex.printStackTrace();
        System.out.println("🔥🔥🔥 Global exception handler 진입");

        ErrorResponse errorResponse = new ErrorResponse(ErrorCode.INTERNAL_SERVER_ERROR);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(errorResponse));
    }
}
