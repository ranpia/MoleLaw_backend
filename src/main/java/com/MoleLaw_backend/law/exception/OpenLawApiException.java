package com.MoleLaw_backend.law.exception;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.common.exception.ErrorCode;

public class OpenLawApiException extends MolelawException {
    public OpenLawApiException(ErrorCode errorCode) {
        super(errorCode);
    }

    public OpenLawApiException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }

    public OpenLawApiException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public OpenLawApiException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}

