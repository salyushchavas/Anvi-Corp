package com.anvicorp.api.mail.dto;

/**
 * Move an entry. When {@code customFolderId} is present it wins (the entry moves
 * into that custom folder); otherwise {@code folder} (a system
 * {@link com.anvicorp.api.mail.entity.MailFolder} enum name) is required.
 */
public record MailMoveRequest(
        String folder,
        String customFolderId
) {
}
