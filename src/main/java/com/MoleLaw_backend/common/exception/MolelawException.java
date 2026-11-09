package com.MoleLaw_backend.common.exception;

import lombok.Getter;

@Getter
public class MolelawException extends RuntimeException {
    private final ErrorCode errorCode;

    public MolelawException(ErrorCode errorCode) {
        this(errorCode, null, null);
    }

    public MolelawException(ErrorCode errorCode, String detailMessage) {
        this(errorCode, detailMessage, null);
    }

    public MolelawException(ErrorCode errorCode, Throwable cause) {
        this(errorCode, null, cause);
    }

    public MolelawException(ErrorCode errorCode, String detailMessage, Throwable cause) {
        super(detailMessage == null ? errorCode.getMessage() : errorCode.getMessage() + " - " + detailMessage, cause);
        this.errorCode = errorCode;
    }
}
