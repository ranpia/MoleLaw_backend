package com.MoleLaw_backend.exception;

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
    @Schema(description = "오류 코드", example = "BAD_REQUEST")
    private String code;
    @Schema(description = "오류 원인 메시지", example = "잘못된 요청입니다.")
    private String message;

    // ✅ ErrorCode 기반 생성자 (기존)
    public ErrorResponse(ErrorCode errorCode) {
        this.status = errorCode.getStatus();
        this.code = errorCode.name();
        this.message = errorCode.getMessage();
    }

    public ErrorResponse(ErrorCode errorCode, String errorMessage) {
        this.status = errorCode.getStatus();
        this.code = errorCode.name();
        this.message = errorMessage;
    }

    // ✅ 단순 메시지로도 생성 가능하도록 오버로드 (추가)
    public ErrorResponse(String message) {
        this.status = 400;
        this.code = "BAD_REQUEST";
        this.message = message;
    }
}
