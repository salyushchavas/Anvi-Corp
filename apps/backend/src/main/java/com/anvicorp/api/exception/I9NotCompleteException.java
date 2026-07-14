package com.anvicorp.api.exception;

/**
 * Thrown when an E-Verify case is requested before the linked Form I-9 has
 * reached COMPLETED, or when the candidate's engagement is in
 * BLOCKED_NO_AUTHORIZATION. Mapped by {@code GlobalExceptionHandler} to 403
 * with {@code code="I9_NOT_COMPLETE"}.
 *
 * Federal rule (E-Verify): a case may be opened only after Form I-9 is
 * complete and no later than the 3rd business day after first day of work.
 * Backs GAP_REPORT A2.
 */
public class I9NotCompleteException extends RuntimeException {
    public I9NotCompleteException(String message) {
        super(message);
    }
}
