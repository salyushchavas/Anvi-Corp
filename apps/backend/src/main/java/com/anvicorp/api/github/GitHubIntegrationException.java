package com.anvicorp.api.github;

/**
 * Thrown by {@link GitHubService} when a call to the GitHub API fails in a
 * way the caller can't recover from inside the same transaction. The
 * {@code GlobalExceptionHandler} converts this to a {@code 502 Bad Gateway}
 * with a structured error body so TE-side surfaces show a clear toast
 * instead of a silent failure.
 *
 * <p>The {@code message} is intended to be operator-readable (no token, no
 * stack-trace dump) — it gets surfaced verbatim to the API client. Callers
 * should keep the message short and avoid leaking PAT / installation tokens
 * even by accident.</p>
 */
public class GitHubIntegrationException extends RuntimeException {

    public GitHubIntegrationException(String message) {
        super(message);
    }

    public GitHubIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
