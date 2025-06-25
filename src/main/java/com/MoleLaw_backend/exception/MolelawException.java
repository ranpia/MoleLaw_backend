package com.MoleLaw_backend.exception;

import lombok.Getter;

@Getter
public class MolelawException extends RuntimeException {
    private final ErrorCode errorCode;
    private final String detailMessage; // ✅ 상세 메시지 별도

    public MolelawException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.detailMessage = null;
    }

    public MolelawException(ErrorCode errorCode, String detailMessage) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.detailMessage = detailMessage;
    }

    public MolelawException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
        this.detailMessage = null;
    }

    public MolelawException(ErrorCode errorCode, String detailMessage, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
        this.detailMessage = detailMessage;
    }

    @Override
    public String getMessage() {
        if (detailMessage != null) {
            return errorCode.getMessage() + " - " + detailMessage;
        }
        return errorCode.getMessage();
    }
}
