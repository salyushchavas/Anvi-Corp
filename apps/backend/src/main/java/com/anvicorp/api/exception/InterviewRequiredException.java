package com.anvicorp.api.exception;

/**
 * Thrown when a recruiter / ERM / HR tries to create an offer or signal
 * conditional selection on an application that hasn't completed an interview.
 * Hard gate — no admin / HR override.
 *
 * Mapped by {@code GlobalExceptionHandler} to 403 with
 * {@code code="INTERVIEW_REQUIRED"} so the recruiter UI can render a clean
 * blocker instead of a raw "bad request" toast.
 *
 * Backs PED rule 3 ("No offer generation until interview scorecard or
 * operations approval") and GAP_REPORT A3.
 */
public class InterviewRequiredException extends RuntimeException {
    public InterviewRequiredException(String message) {
        super(message);
    }
}
