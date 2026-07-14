package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Document vault row. Phase 3 only writes {@code category=SIGNED_OFFER}; the
 * onboarding phase (Phase 4) extends the categories to W4, I9, ACH, etc.
 *
 * <p>{@code storageKey} is the path / object key into whatever storage
 * backend is configured (S3 / R2 / on-disk vault). Phase 3 uses the local
 * on-disk vault path the existing infrastructure already provides.</p>
 *
 * <p>{@code sensitivity} drives display + retention; PII rows never appear in
 * non-staff list responses.</p>
 */
@Entity
@Table(name = "documents", indexes = {
        @Index(name = "idx_documents_owner", columnList = "owner_user_id"),
        @Index(name = "idx_documents_category", columnList = "category"),
        @Index(name = "idx_documents_created_at", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "category", nullable = false, length = 40)
    private String category;

    @Column(name = "sensitivity", nullable = false, length = 20,
            columnDefinition = "varchar(20) not null default 'NORMAL'")
    @Builder.Default
    private String sensitivity = "NORMAL";

    @Column(name = "uploaded_by_id")
    private UUID uploadedById;

    /**
     * Phase 4 — AES-256-GCM envelope metadata (IV + tag) when the content
     * bytes are encrypted at rest. Stored as JSON like
     * {@code {"alg":"AES-256-GCM","iv":"...base64...","tag":"...base64..."}}
     * Null for unencrypted documents (sensitivity=NORMAL).
     */
    @Column(name = "encryption_metadata_json", columnDefinition = "TEXT")
    private String encryptionMetadataJson;

    /** Phase 4 — soft delete marker. Reads filter this out by default. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
