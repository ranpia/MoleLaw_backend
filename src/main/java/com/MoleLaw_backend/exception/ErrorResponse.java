package com.MoleLaw_backend.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@NoArgsConstructor
public class ErrorResponse {
    private int status;
    private String code;
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
