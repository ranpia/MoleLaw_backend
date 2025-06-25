package com.MoleLaw_backend.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {
    INVALID_REQUEST(400, "잘못된 요청입니다."),
    UNAUTHORIZED(401, "인증이 필요합니다."),
    FORBIDDEN(403, "접근 권한이 없습니다."),
    NOT_FOUND(404, "대상을 찾을 수 없습니다."),
    INTERNAL_SERVER_ERROR(500, "서버 내부 오류입니다."),
    LAW_NOT_FOUND(404, "해당 법령을 찾을 수 없습니다."),
    GPT_FAILED(502, "GPT 처리에 실패했습니다."),
    USER_NOT_FOUND(404, "해당 사용자를 찾을 수 없습니다.");
    private final int status;
    private final String message;

    ErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

}