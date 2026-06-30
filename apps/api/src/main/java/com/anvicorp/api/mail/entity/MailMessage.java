package com.anvicorp.api.mail.entity;

import com.anvicorp.api.crypto.AesGcmCryptoConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A single internal mail message ("a mail is a DB row"). Bodies are encrypted at
 * rest with the shared {@link AesGcmCryptoConverter} (AES-256-GCM, the A1 PII
 * key). Subject is intentionally NOT encrypted so it stays DB-searchable.
 * Recipients live in {@code mail_message_recipients}; each participant's
 * per-folder view lives in {@code mail_mailbox_entries}.
 *
 * <p>{@code threadId} = the root message id (a root message points at itself);
 * replies copy the root's threadId and set {@code inReplyTo}.</p>
 *
 * <p>The {@code draft*} columns hold the raw recipient addresses ONLY while this
 * is a draft (so a draft round-trips in the composer); they are cleared on send,
 * where {@code mail_message_recipients} rows take over. They are addresses, not
 * content, so they are intentionally not encrypted.</p>
 */
@Entity
@Table(name = "mail_messages",
        indexes = {
                @Index(name = "idx_mail_message_thread", columnList = "thread_id"),
                @Index(name = "idx_mail_message_sender", columnList = "sender_account_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailMessage {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "sender_account_id", nullable = false)
    private UUID senderAccountId;

    @Column(length = 500)
    private String subject;

    @Column(name = "body_text", columnDefinition = "TEXT")
    @Convert(converter = AesGcmCryptoConverter.class)
    private String bodyText;

    @Column(name = "body_html", columnDefinition = "TEXT")
    @Convert(converter = AesGcmCryptoConverter.class)
    private String bodyHtml;

    @Column(name = "thread_id")
    private UUID threadId;

    /** Parent message id for a reply (null for a root message). */
    @Column(name = "in_reply_to")
    private UUID inReplyTo;

    @Column(name = "has_attachments", nullable = false)
    @Builder.Default
    private Boolean hasAttachments = Boolean.FALSE;

    // Raw recipient addresses kept ONLY while this is a draft (so a draft
    // round-trips in the composer). Cleared on send; sent messages use
    // mail_message_recipients rows. Not encrypted (addresses, not content).
    @Column(name = "draft_to", columnDefinition = "TEXT")
    private String draftTo;

    @Column(name = "draft_cc", columnDefinition = "TEXT")
    private String draftCc;

    @Column(name = "draft_bcc", columnDefinition = "TEXT")
    private String draftBcc;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
