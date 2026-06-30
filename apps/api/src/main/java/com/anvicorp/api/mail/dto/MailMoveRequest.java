package com.anvicorp.api.mail.dto;

/**
 * Move an entry to a system folder (one of the {@link com.anvicorp.api.mail.entity.MailFolder}
 * names). Custom folders are a later phase.
 */
public record MailMoveRequest(
        String folder
) {
}
