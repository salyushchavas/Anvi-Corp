package com.anvicorp.api.mail.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Row in a folder/search/starred listing. Deliberately carries NO body (listing
 * never decrypts bodies) — only subject + participants + flags. {@code to} holds
 * the visible TO/CC participants (BCC is never shown in listings).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MailMessageSummary(
        String entryId,
        String messageId,
        String threadId,
        String folder,
        String subject,
        MailParticipant from,
        List<MailParticipant> to,
        boolean isRead,
        boolean isStarred,
        boolean isImportant,
        boolean hasAttachments,
        Instant createdAt
) {
}
