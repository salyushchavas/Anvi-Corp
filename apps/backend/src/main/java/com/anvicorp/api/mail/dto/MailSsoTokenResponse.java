package com.anvicorp.api.mail.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response body for the careers→mail SSO handoff
 * ({@code POST /api/v1/mail/sso-token}). Shape is a strict subset of
 * {@link MailAuthResponse} so the frontend can reuse the same
 * {@code setMailAuth({ token, refreshToken, account })} helper it
 * already uses after a mail login.
 *
 * <p>The paired mailbox address is echoed as {@code email} for parity
 * with the login response; the client uses it to build the persisted
 * {@code mail.account} shape.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MailSsoTokenResponse(
        String token,
        String refreshToken,
        Long accessTokenExpiresInSeconds,
        String accountId,
        String email,
        String displayName,
        String role,
        Boolean mustChangePassword,
        String domainId
) {
}
