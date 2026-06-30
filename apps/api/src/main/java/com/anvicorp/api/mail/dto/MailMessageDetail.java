package com.anvicorp.api.mail.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Full message as seen through ONE of the caller's mailbox entries. Bodies are
 * decrypted on read by the AES converter. {@code bcc} is populated only with the
 * BCC entries the caller is allowed to see (all of them if the caller is the
 * sender; their own if the caller was BCC'd; empty otherwise). The {@code draft*}
 * fields are populated only for a DRAFTS entry so the composer can re-hydrate.
 *
 * <p>Attachments arrive in a later phase ({@code hasAttachments} is always false
 * for now and there is no attachment list field yet).</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MailMessageDetail(
        String entryId,
        String messageId,
        String threadId,
        String inReplyTo,
        String folder,
        String subject,
        String bodyText,
        String bodyHtml,
        MailParticipant from,
        List<MailParticipant> to,
        List<MailParticipant> cc,
        List<MailParticipant> bcc,
        boolean isRead,
        boolean isStarred,
        boolean isImportant,
        boolean hasAttachments,
        String draftTo,
        String draftCc,
        String draftBcc,
        Instant createdAt
) {
}
