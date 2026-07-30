package com.anvicorp.api.auth;

/**
 * Thrown by {@link RegistrationRateLimiter} when a caller has exceeded
 * its budget. The AuthController handler maps this to HTTP 429 with a
 * Retry-After header so a well-behaved client backs off automatically.
 */
public class RateLimitException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
