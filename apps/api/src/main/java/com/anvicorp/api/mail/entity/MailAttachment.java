package com.anvicorp.api.mail.entity;

import jakarta.persistence.Column;
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
 * A message attachment. The bytes live encrypted at rest in S3 (AES-256-GCM via
 * the shared cipher — the same key that encrypts message bodies); this row holds
 * only metadata + the opaque storage key ("s3:&lt;objectKey&gt;"). Draft-anchored:
 * message_id points at the draft's message and rides along when the draft is sent.
 * {@code sizeBytes} is the PLAINTEXT size; the S3 object is larger (encrypted envelope).
 */
@Entity
@Table(name = "mail_attachments",
        indexes = @Index(name = "idx_mail_attachment_message", columnList = "message_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailAttachment {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(nullable = false, length = 255)
    private String filename;

    @Column(name = "content_type", length = 255)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Opaque store key, e.g. "s3:anvi-mail/mail/attachments/&lt;uuid&gt;.bin". Never exposed. */
    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
