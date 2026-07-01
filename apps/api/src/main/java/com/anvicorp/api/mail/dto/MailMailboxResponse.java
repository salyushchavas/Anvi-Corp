package com.anvicorp.api.mail.dto;

import java.time.Instant;

/** Mailbox view for admin lists/actions. Deliberately carries NO password field. */
public record MailMailboxResponse(
        String accountId,
        String domainId,
        String domainName,
        String localPart,
        String email,
        String displayName,
        String role,
        String status,
        boolean mustChangePassword,
        boolean requireChangeOnFirstLogin,
        long quotaBytes,
        Instant createdAt
) {
}
