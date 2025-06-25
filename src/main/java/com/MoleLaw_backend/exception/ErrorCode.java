package com.MoleLaw_backend.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    INVALID_REQUEST(400, "잘못된 요청입니다."),
    UNAUTHORIZED(401, "인증이 필요합니다."),
    FORBIDDEN(403, "접근 권한이 없습니다."),
    NOT_FOUND(404, "대상을 찾을 수 없습니다."),
    INTERNAL_SERVER_ERROR(500, "서버 내부 오류입니다."),
    OPENLAW_API_FAILURE(HttpStatus.BAD_GATEWAY.value(), "공공법령 API 통신에 실패했습니다"),
    OPENLAW_INVALID_RESPONSE(HttpStatus.BAD_REQUEST.value(), "공공법령 API 응답이 올바르지 않습니다"),
    GPT_API_FAILURE(HttpStatus.BAD_GATEWAY.value(), "GPT 응답 생성 중 오류 발생"),
    GPT_EMPTY_RESPONSE(HttpStatus.INTERNAL_SERVER_ERROR.value(), "GPT 응답이 비어 있음"),
    USER_NOT_FOUND(404, "해당 사용자를 찾을 수 없습니다.");
    private final int status;
    private final String message;

    ErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

}