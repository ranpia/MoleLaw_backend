package com.MoleLaw_backend.service.law.tool;

import com.MoleLaw_backend.exception.ErrorCode;
import com.MoleLaw_backend.exception.OpenLawApiException;

/** Sanitized transport failure: never retains a URI containing the API credential. */
public class OpenLawRequestException extends OpenLawApiException {
    private final boolean retryable;

    public OpenLawRequestException(boolean retryable) {
        super(ErrorCode.OPENLAW_API_FAILURE);
        this.retryable = retryable;
    }

    public boolean isRetryable() { return retryable; }
}
