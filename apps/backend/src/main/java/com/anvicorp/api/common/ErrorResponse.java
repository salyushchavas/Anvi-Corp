package com.anvicorp.api.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Single JSON error shape emitted across the backend, so the frontend can
 * render any failure consistently and a user-reported error is greppable in the
 * backend log via {@link #traceId}.
 *
 * <p>The {@code error} field duplicates {@link #message} for
 * backward-compatibility with clients that read {@code response.data.error};
 * new callers should prefer {@code message}. Both always carry the same string.</p>
 *
 * <p>{@code details} carries handler-specific payload (e.g. a field-error map
 * from a validation failure). Null (omitted from JSON) when there's nothing to
 * attach.</p>
 *
 * <p>{@code code} is a machine-readable identifier the frontend can pattern-match
 * on (e.g. {@code MAIL_LOGIN_FAILED}, {@code MAIL_NOT_CONFIGURED}). Null when no
 * stable code is defined for the handler.</p>
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
