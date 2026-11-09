package com.MoleLaw_backend.dto.response;

import com.MoleLaw_backend.util.exception.ErrorCode;

import io.swagger.v3.oas.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@NoArgsConstructor
public class ErrorResponse {
    @Schema(description = "HTTP 상태 코드", example = "400")
    private int status;
    @Schema(description = "오류 코드", example = "INVALID_REQUEST")
    private String code;
    @Schema(description = "오류 원인 메시지", example = "잘못된 요청입니다.")
    private String message;

    public ErrorResponse(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public ErrorResponse(ErrorCode errorCode, String errorMessage) {
        this.status = errorCode.getStatus();
        this.code = errorCode.name();
        this.message = errorMessage;
    }

}
