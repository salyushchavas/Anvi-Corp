package com.anvicorp.api.mail.dto;

/** A custom folder + its live message counts. */
public record MailCustomFolderResponse(
        String id,
        String name,
        long total,
        long unread
) {
}
