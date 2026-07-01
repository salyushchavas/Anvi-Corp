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
 * <p>{@code attachments} lists the message's attachments (empty when none); each
 * is downloaded through the walled proxy at {@code /api/mail/attachments/{id}}.</p>
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
        Instant createdAt,
        List<MailAttachmentResponse> attachments,
        /** Non-null when this entry lives in a custom folder (A6 precedence). */
        String customFolderId
) {
}
