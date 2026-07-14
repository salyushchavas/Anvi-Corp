package com.anvicorp.api.judge0;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Normalized result returned by {@link Judge0Service#executeAndAwait}.
 *
 * <p>The {@code statusId} mirrors Judge0's status enum
 * (1=In Queue, 2=Processing, 3=Accepted, 4=Wrong Answer,
 * 5=Time Limit Exceeded, 6=Compilation Error, 7..12=Runtime errors,
 * 13=Internal Error) plus negative sentinel values for failure modes that
 * happen on OUR side and never reached a real Judge0 status:</p>
 * <ul>
 *   <li>{@code -1} — RapidAPI quota exceeded (429).</li>
 *   <li>{@code -2} — Polling exhausted before Judge0 finished
 *       (sandbox-side timeout, distinct from the Time Limit Exceeded
 *       Judge0 status 5).</li>
 *   <li>{@code -3} — Judge0 / network unavailable.</li>
 *   <li>{@code -4} — Service not configured (no API key).</li>
 * </ul>
 *
 * <p>The frontend keys off {@code statusId} for branch logic and renders
 * {@code statusDescription} for the human-readable label.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CodeExecutionResult(
        String stdout,
        String stderr,
        String compileOutput,
        int statusId,
        String statusDescription,
        String time,
        Integer memory,
        Integer exitCode
) {

    public static final int STATUS_QUOTA_EXCEEDED = -1;
    public static final int STATUS_POLL_TIMED_OUT = -2;
    public static final int STATUS_SANDBOX_UNAVAILABLE = -3;
    public static final int STATUS_NOT_CONFIGURED = -4;

    public static CodeExecutionResult notConfigured() {
        return new CodeExecutionResult(
                null, null, null,
                STATUS_NOT_CONFIGURED,
                "Code execution sandbox not configured on this server.",
                null, null, null);
    }

    public static CodeExecutionResult quotaExceeded() {
        return new CodeExecutionResult(
                null, null, null,
                STATUS_QUOTA_EXCEEDED,
                "Sandbox quota exceeded — try again later.",
                null, null, null);
    }

    public static CodeExecutionResult pollTimedOut() {
        return new CodeExecutionResult(
                null, null, null,
                STATUS_POLL_TIMED_OUT,
                "Sandbox timed out waiting for a result.",
                null, null, null);
    }

    public static CodeExecutionResult unavailable() {
        return new CodeExecutionResult(
                null, null, null,
                STATUS_SANDBOX_UNAVAILABLE,
                "Sandbox unavailable. Try again in a moment.",
                null, null, null);
    }
}
