package com.anvicorp.api.entity;

import com.anvicorp.api.erm.documents.SkyzenDocument;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * ERM Phase 8.2 — one row per (packet, SkyzenDocument) combo. The
 * blank PDF the intern downloads lives as a static asset at
 * {@code /document-templates/{filename}} — no per-task snapshot column
 * needed since the file is committed to the repo (versioned by git,
 * cacheable by CDN). The intern's filled-in upload still flows through
 * {@link com.anvicorp.api.intern.DocumentVaultService} with the
 * sensitivity tag carried over from {@link SkyzenDocument}.
 *
 * <p>Lifecycle: {@code PENDING → SUBMITTED → UNDER_REVIEW → ACCEPTED |
 * REJECTED | RESEND_REQUESTED}; {@code WAIVED} is the SUPER_ADMIN
 * escape hatch.</p>
 */
@Entity
@Table(name = "document_tasks",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_document_tasks_packet_document_key",
                columnNames = {"packet_id", "document_key"}),
        indexes = {
                @Index(name = "idx_document_tasks_packet_status",
                        columnList = "packet_id, status"),
                @Index(name = "idx_document_tasks_status_submitted",
                        columnList = "status, submitted_at"),
                @Index(name = "idx_document_tasks_reviewer",
                        columnList = "reviewed_by_id, reviewed_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentTask {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "packet_id", nullable = false)
    private UUID packetId;

    /** ERM Phase 8.2 — replaces template_id + snapshot columns.
     *  Persisted as VARCHAR via {@link EnumType#STRING}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "document_key", nullable = false, length = 80)
    private SkyzenDocument documentKey;

    @Column(name = "task_instructions", columnDefinition = "TEXT")
    private String taskInstructions;

    /** PENDING | SUBMITTED | UNDER_REVIEW | ACCEPTED | REJECTED |
     *  RESEND_REQUESTED | WAIVED. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "uploaded_file_id")
    private UUID uploadedFileId;

    @Column(name = "download_count", nullable = false)
    @Builder.Default
    private Integer downloadCount = 0;

    @Column(name = "last_downloaded_at")
    private Instant lastDownloadedAt;

    /**
     * UUID of the ERM user who most recently downloaded the upload. Set by
     * the {@code /erm/document-review/tasks/{id}/file} endpoint together
     * with {@link #lastDownloadedAt} and {@link #downloadCount}. Pass 2
     * verify-after-download gate uses non-null {@code lastDownloadedAt} to
     * authorize an ACCEPT decision — kept on the row (not on the user) so
     * the "downloaded" state is per-document and survives across ERM
     * agents.
     */
    @Column(name = "downloaded_by_id")
    private UUID downloadedById;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewed_by_id")
    private UUID reviewedById;

    @Column(name = "review_reason_code", length = 80)
    private String reviewReasonCode;

    /** Shown verbatim to the intern on REJECT / RESEND_REQUESTED. */
    @Column(name = "review_comments", columnDefinition = "TEXT")
    private String reviewComments;

    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(name = "waived_at")
    private Instant waivedAt;

    @Column(name = "waived_by_id")
    private UUID waivedById;

    @Column(name = "waived_reason", columnDefinition = "TEXT")
    private String waivedReason;

    /** ERM-only — NEVER returned to INTERN DTOs. */
    @Column(name = "internal_note", columnDefinition = "TEXT")
    private String internalNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
