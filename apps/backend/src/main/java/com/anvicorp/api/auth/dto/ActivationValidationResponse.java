package com.anvicorp.api.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Result of {@code GET /auth/activate/validate?token=...}. Returned only
 * when the token is live (exists, not used, not expired). Lets the
 * activation page render "Set the password for &lt;email&gt;" before the
 * user types anything, so a stale link doesn't waste a password entry.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActivationValidationResponse(
        String email,
        String fullName,
        String role,
        Instant expiresAt
) {}
