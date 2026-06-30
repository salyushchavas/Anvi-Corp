package com.anvicorp.api.mail.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Login credentials. {@code email} is a full mail address
 * ({@code localPart@domain}); validated only as non-blank so that EVERY bad
 * login (unknown account, wrong domain, suspended, bad password, malformed
 * address) returns the same generic 401 — no enumeration.
 */
public record MailLoginRequest(
        @NotBlank String email,
        @NotBlank String password
) {
}
