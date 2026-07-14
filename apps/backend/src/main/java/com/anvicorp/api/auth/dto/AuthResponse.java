package com.anvicorp.api.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Login + registration response. Includes the access + refresh token pair
 * issued for a freshly-created session.
 *
 * <p>Security note: the verification code itself is NEVER returned by any
 * endpoint. It is delivered only via email (real SMTP in prod; the backend
 * log when {@code app.notification.surface-stub=true} in dev for the
 * developer's eyes only — never round-tripped to the client).</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthResponse(
        String token,
        String refreshToken,
        Long accessTokenExpiresInSeconds,
        String userId,
        String email,
        String fullName,
        List<String> roles,
        Boolean emailVerified,
        String applicantId,
        /**
         * TRUE for staff accounts created by SUPER_ADMIN with a temp
         * password. The frontend MUST redirect the user to the
         * force-change-password screen until this flips false (it does so
         * automatically after a successful change-password call). The
         * server also enforces a route gate so a malicious client cannot
         * bypass the redirect.
         */
        Boolean mustChangePassword
) {}
