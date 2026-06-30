package com.anvicorp.api.mail.dto;

import java.util.UUID;

/**
 * Lightweight message projection for listings/search — selects metadata only,
 * NEVER the body columns, so the AES converter is not invoked (lists don't
 * decrypt bodies). Populated by a JPQL constructor expression.
 */
public record MailMessageHeader(
        UUID id,
        UUID senderAccountId,
        String subject,
        UUID threadId,
        Boolean hasAttachments
) {
}
