package com.MoleLaw_backend.law.service.tool;

import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.law.exception.OpenLawApiException;

/** Sanitized transport failure: never retains a URI containing the API credential. */
public class OpenLawRequestException extends OpenLawApiException {
    private final boolean retryable;

    public OpenLawRequestException(boolean retryable) {
        super(ErrorCode.OPENLAW_API_FAILURE);
        this.retryable = retryable;
    }

    public boolean isRetryable() { return retryable; }
}
