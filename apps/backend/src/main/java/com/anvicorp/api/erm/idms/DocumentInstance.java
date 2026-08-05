package com.anvicorp.api.erm.idms;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * IDMS Phase 2 — a per-intern instantiation of an EditableTemplate.
 *
 * <p>Snapshots the template's {@code canonical_html} + {@code field_schema}
 * onto the row at create time so template edits never mutate in-flight
 * documents (survey §A3). The DB partial UNIQUE on
 * {@code (intern_lifecycle_id, template_id) WHERE status NOT IN
 * ('VOIDED','SUPERSEDED','REVOKED')} keeps the ERM from stacking two
 * live copies of the same template for the same intern.</p>
 */
@Entity
@Table(name = "document_instances", indexes = {
        @Index(name = "idx_di_lifecycle_status", columnList = "intern_lifecycle_id, status"),
        @Index(name = "idx_di_template", columnList = "template_id"),
        @Index(name = "idx_di_intern_user", columnList = "intern_user_id"),
        @Index(name = "idx_di_status_updated", columnList = "status, updated_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentInstance {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "template_id", nullable = false)
    private UUID templateId;

    @Column(name = "intern_lifecycle_id", nullable = false)
    private UUID internLifecycleId;

    @Column(name = "intern_user_id", nullable = false)
    private UUID internUserId;

    @Column(name = "created_by_erm_id", nullable = false)
    private UUID createdByErmId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    @Builder.Default
    private DocumentInstanceStatus status = DocumentInstanceStatus.DRAFT;

    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 1;

    /** True while the intern can't edit — set true on SEND, flipped false on RETURN. */
    @Column(name = "intern_locked", nullable = false)
    @Builder.Default
    private Boolean internLocked = Boolean.FALSE;

    // ── Snapshot at create ──────────────────────────────────────────────────

    @Column(name = "template_title", nullable = false, length = 200)
    private String templateTitle;

    @Column(name = "template_key", nullable = false, length = 100)
    private String templateKey;

    /** docx-preview canonical HTML with data-field-id anchor spans. */
    @Column(name = "snapshot_canonical_html", nullable = false, columnDefinition = "TEXT")
    private String snapshotCanonicalHtml;

    /** JSONB — array of FieldEntry objects (id, name, type, assignee, required, defaultSource). */
    @Column(name = "snapshot_field_schema", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String snapshotFieldSchemaJson;

    // ── Supersede + final PDF ──────────────────────────────────────────────

    /** Points at the prior FINALIZED instance this one replaced. Nullable. */
    @Column(name = "supersedes_id")
    private UUID supersedesId;

    /** Vault Document row (category=EDITABLE_DOC_INSTANCE_PDF, PII-class). */
    @Column(name = "final_pdf_document_id")
    private UUID finalPdfDocumentId;

    // ── Correction loop ────────────────────────────────────────────────────

    @Column(name = "return_reason_code", length = 80)
    private String returnReasonCode;

    @Column(name = "return_comments", columnDefinition = "TEXT")
    private String returnComments;

    // ── Revocation ─────────────────────────────────────────────────────────

    @Column(name = "revoke_reason_code", length = 80)
    private String revokeReasonCode;

    @Column(name = "revoke_comments", columnDefinition = "TEXT")
    private String revokeComments;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by_id")
    private UUID revokedById;

    // ── Per-transition timestamps ──────────────────────────────────────────

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "intern_submitted_at")
    private Instant internSubmittedAt;

    @Column(name = "returned_at")
    private Instant returnedAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    /** Set on every ERM detail-fetch of an INTERN_SUBMITTED instance — the
     *  "viewed before verify" gate. */
    @Column(name = "last_erm_viewed_at")
    private Instant lastErmViewedAt;

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
