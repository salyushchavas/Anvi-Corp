package com.anvicorp.api.mail.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A sender/recipient as shown to the caller. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MailParticipant(
        String accountId,
        String email,
        String displayName
) {
}
