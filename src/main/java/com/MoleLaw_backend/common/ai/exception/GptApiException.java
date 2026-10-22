package com.MoleLaw_backend.common.ai.exception;

import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.common.exception.ErrorCode;

public class GptApiException extends MolelawException {
    public GptApiException(ErrorCode errorCode) {
        super(errorCode);
    }

    public GptApiException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }

    public GptApiException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public GptApiException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
