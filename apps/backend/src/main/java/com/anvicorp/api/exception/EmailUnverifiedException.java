package com.anvicorp.api.exception;

/**
 * Thrown when a CANDIDATE caller hits a gated endpoint (openings list,
 * apply) without a verified email. Mapped by {@code GlobalExceptionHandler}
 * to 403 with {@code code="EMAIL_UNVERIFIED"} so the frontend can render the
 * verify-email prompt instead of a raw error.
 */
public class EmailUnverifiedException extends RuntimeException {
    public EmailUnverifiedException(String message) {
        super(message);
    }
}
