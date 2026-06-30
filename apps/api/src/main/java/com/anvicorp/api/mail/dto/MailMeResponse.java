package com.anvicorp.api.mail.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Current mail principal — backs GET /api/mail/me. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MailMeResponse(
        String accountId,
        String email,
        String displayName,
        String domainId,
        String role,
        Boolean mustChangePassword
) {
}
