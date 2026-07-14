package com.anvicorp.api.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Single response shape every {@link GlobalExceptionHandler} branch
 * emits, so the frontend can render any error consistently and a
 * user-reported failure is greppable in the backend log via
 * {@link #traceId}.
 *
 * <p>The {@code error} field duplicates {@link #message} for
 * backward-compatibility with the 191 existing call sites that read
 * {@code response.data.error} — new callers should prefer
 * {@code message}. Both always carry the same string.</p>
 *
 * <p>{@code details} carries handler-specific payload (e.g. the
 * field-error map from a validation failure, the {@code missing}
 * array from a reporting-structure-incomplete refusal). Null
 * (omitted from JSON) when there's nothing to attach.</p>
 *
 * <p>{@code code} is a machine-readable identifier the frontend can
 * pattern-match on (e.g. {@code LIFECYCLE_CLOSED},
 * {@code CHECK_VIOLATION}). Null when no stable code is defined for
 * the handler.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        int status,
        String error,
        String message,
        String code,
        String traceId,
        Instant timestamp,
        Map<String, Object> details
) {
}
