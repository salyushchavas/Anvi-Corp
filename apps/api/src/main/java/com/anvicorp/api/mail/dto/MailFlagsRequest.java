package com.anvicorp.api.mail.dto;

/** Partial flag update on the caller's entry; null fields are left unchanged. */
public record MailFlagsRequest(
        Boolean isRead,
        Boolean isStarred,
        Boolean isImportant
) {
}
