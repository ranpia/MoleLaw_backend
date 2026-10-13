package com.MoleLaw_backend.law.service.tool;

public class LegalToolException extends RuntimeException {
    public enum Reason { FORBIDDEN, UNKNOWN_ID, DUPLICATE, BUDGET_EXHAUSTED, INVALID_RESPONSE, API_FAILURE }
    private final Reason reason;
    private final boolean retryable;

    public LegalToolException(Reason reason, boolean retryable) {
        super(reason.name());
        this.reason = reason;
        this.retryable = retryable;
    }

    public Reason getReason() { return reason; }
    public boolean isRetryable() { return retryable; }
}
