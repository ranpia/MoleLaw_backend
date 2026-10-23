package com.MoleLaw_backend.law.exception;

/** Safe diagnostic codes only; never retains the model output or Jackson exception. */
public final class EvidenceDecisionValidationException extends IllegalArgumentException {
    public enum Code { FIELD_CONSTRAINT, ACTION_CONTRACT, UNKNOWN_EVIDENCE_ID, JSON_CONTRACT }

    private final Code code;

    public EvidenceDecisionValidationException(Code code) {
        super("Invalid evidence decision");
        this.code = code;
    }

    public Code getCode() { return code; }
}
