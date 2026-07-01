package com.anvicorp.api.mail.dto;

/**
 * SHOW-ONCE provisioning response for create/reset. {@code oneTimePassword} is
 * the ONLY place plaintext ever appears — it is never persisted, never logged,
 * and never returned by any GET. Display it to the admin once, then it's gone.
 */
public record MailCredentialResponse(
        String accountId,
        String email,
        String oneTimePassword,
        boolean mustChangePassword
) {
}
