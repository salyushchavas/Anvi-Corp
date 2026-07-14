package com.anvicorp.api.mail.dto;

import java.time.Instant;

public record MailDomainResponse(
        String id,
        String name,
        String displayName,
        boolean active,
        long accountCount,
        Instant createdAt
) {
}
