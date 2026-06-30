package com.anvicorp.api.mail.dto;

/** Per-folder total + unread counts for the caller. */
public record MailFolderCount(
        String folder,
        long total,
        long unread
) {
}
