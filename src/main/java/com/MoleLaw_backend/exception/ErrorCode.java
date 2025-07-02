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
    USER_NOT_FOUND(404, "해당 사용자를 찾을 수 없습니다."),
    USER_ID_NULL(HttpStatus.UNAUTHORIZED.value(), "사용자 정보가 유효하지 않습니다."),
    CHATROOM_NOT_FOUND(HttpStatus.NOT_FOUND.value(), "채팅방을 찾을 수 없습니다."),
    UNAUTHORIZED_CHATROOM_ACCESS(HttpStatus.FORBIDDEN.value(), "본인의 채팅방이 아닙니다."),
    KEYWORD_EXTRACTION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR.value(), "키워드 추출에 실패했습니다."),
    USER_NOT_AUTHENTICATED(HttpStatus.UNAUTHORIZED.value(), "인증되지 않은 사용자입니다.");
    private final int status;
    private final String message;

    ErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

}