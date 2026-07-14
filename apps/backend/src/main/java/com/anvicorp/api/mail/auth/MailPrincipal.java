package com.anvicorp.api.mail.auth;

import com.anvicorp.api.mail.entity.MailRole;

import java.util.UUID;

/**
 * Authenticated mail identity placed in the SecurityContext by
 * {@link MailJwtAuthenticationFilter} and injected into mail controllers via
 * {@code @AuthenticationPrincipal MailPrincipal}. A mail-specific principal type
 * — should a future careers chain add its own filters/principal, those filters
 * inspect their own principal type and ignore this one.
 */
public record MailPrincipal(
        UUID accountId,
        String email,
        String displayName,
        UUID domainId,
        MailRole role,
        boolean mustChangePassword
) {
}
