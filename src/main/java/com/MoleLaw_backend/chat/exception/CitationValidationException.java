package com.MoleLaw_backend.chat.exception;

import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.common.exception.ErrorCode;

public class CitationValidationException extends GptApiException {
    public enum Reason { MISSING_EVIDENCE, DUPLICATE_EVIDENCE, MODEL_URL, UNKNOWN_ID, MALFORMED_MARKER, MISSING_CITATION }
    private final Reason reason;

    public CitationValidationException(Reason reason) {
        super(ErrorCode.GPT_API_FAILURE);
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    public boolean repairable() {
        return reason != Reason.MISSING_EVIDENCE && reason != Reason.DUPLICATE_EVIDENCE;
    }
}
