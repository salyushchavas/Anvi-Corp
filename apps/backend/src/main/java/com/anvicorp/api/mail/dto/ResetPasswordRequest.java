package com.anvicorp.api.mail.dto;

/**
 * Admin password reset. {@code password} is optional — blank → the server
 * generates a strong one-time password. The new password is returned ONCE in
 * the {@link MailCredentialResponse}; never stored in plaintext or logged.
 */
public record ResetPasswordRequest(
        String password
) {
}
