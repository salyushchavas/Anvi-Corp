package com.anvicorp.api.exception;

/**
 * Phase 8 — thrown when a write is attempted against an intern lifecycle
 * that is no longer in an editable state per {@code LifecycleAccessPolicy}.
 * Controller layer maps this to {@code 409 CONFLICT} with the reason
 * surfaced as-is (no leak of internal state).
 */
public class LifecycleClosedException extends RuntimeException {
    public LifecycleClosedException(String message) {
        super(message);
    }
}
