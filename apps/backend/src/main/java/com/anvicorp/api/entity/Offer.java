package com.anvicorp.api.entity;

import com.anvicorp.api.enums.CompensationFrequency;
import com.anvicorp.api.enums.OfferStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
        name = "offers",
        indexes = {
                @Index(name = "idx_offer_application", columnList = "application_id"),
                @Index(name = "idx_offer_status_expires", columnList = "status, expires_at"),
                @Index(name = "idx_offer_created_at", columnList = "created_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Offer {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    @Column(name = "compensation_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal compensationAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "compensation_frequency", nullable = false)
    private CompensationFrequency compensationFrequency;

    @Column(name = "compensation_currency", nullable = false, length = 3)
    @Builder.Default
    private String compensationCurrency = "USD";

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "expected_end_date")
    private LocalDate expectedEndDate;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private OfferStatus status = OfferStatus.DRAFT;

    @Column(name = "additional_terms", columnDefinition = "TEXT")
    private String additionalTerms;

    @Column(name = "letter_content", columnDefinition = "TEXT", nullable = false)
    private String letterContent;

    @Column(name = "decline_reason", columnDefinition = "TEXT")
    private String declineReason;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    // ── Phase 3 — offer-letter doc-spec fields ─────────────────────────────
    // Legacy external-signing envelope id + template id from the original
    // third-party signing integration that's been removed (signing now
    // runs through IDMS — see OfferIdmsSigningService). Columns kept so
    // pre-removal rows still display + the unique index remains valid.

    /** Legacy external-signing envelope id (pre-IDMS). Always null on new
     *  offers; preserved for historical rows. Column name retained to
     *  avoid a data-touching rename. */
    @Column(name = "docusign_envelope_id", length = 80, unique = true)
    private String legacyEnvelopeId;

    /** Legacy external-signing template id (pre-IDMS). Always null on new
     *  offers; preserved for historical rows. */
    @Column(name = "docusign_template_id", length = 80)
    private String legacyTemplateId;

    /** Stamped at sign time by {@link
     *  com.anvicorp.api.intern.OfferIdmsSigningService#finalizeIdmsSigning}. */
    @Column(name = "signed_at")
    private Instant signedAt;

    /**
     * Phase 8.6.2 — applicant's typed name captured at sign time on the
     * in-house signing page. Auto-derived from {@code applicant.fullName}
     * when the applicant draws (rather than types) the signature.
     * Nullable for legacy rows that completed before IDMS signing
     * existed.
     */
    @Column(name = "signed_by_typed_name", length = 200)
    private String signedByTypedName;

    /**
     * Phase 8.6.2.1 — base64 data URL of the drawn signature (PNG). Stored
     * as TEXT so the average ~5-30 KB payload fits without overflow.
     * Null for legacy externally-signed rows. Render with {@code <img src>}.
     */
    @Column(name = "signed_signature_image", columnDefinition = "TEXT")
    private String signedSignatureImage;

    @Column(name = "voided_at")
    private Instant voidedAt;

    /** Min 10 chars at controller layer; staff-only in applicant DTOs. */
    @Column(name = "voided_reason", columnDefinition = "TEXT")
    private String voidedReason;

    /** Role name shown in the letter. */
    @Column(name = "role_title", length = 200)
    private String roleTitle;

    /** Free-text compensation summary that appears via the template merge. */
    @Column(name = "compensation_summary", length = 500)
    private String compensationSummary;

    @Column(name = "worksite", length = 200)
    private String worksite;

    @Column(name = "expected_hours_per_week")
    private Integer expectedHoursPerWeek;

    /** Archive of the signed PDF in {@code documents} (category=SIGNED_OFFER). */
    @Column(name = "signed_pdf_document_id")
    private UUID signedPdfDocumentId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ── ERM Phase 4 — decision-context + re-offer + reminders ──────────────

    /** Coded reason for a VOID. Distinct from legacy {@code voided_reason} free text. */
    @Column(name = "void_reason_code", length = 80)
    private String voidReasonCode;

    /** ERM-only structured void reason; never returned to INTERN. */
    @Column(name = "void_reason_text", columnDefinition = "TEXT")
    private String voidReasonText;

    /** ERM-only appendable notes. */
    @Column(name = "internal_notes", columnDefinition = "TEXT")
    private String internalNotes;

    @Column(name = "reminder_count", nullable = false,
            columnDefinition = "integer not null default 0")
    @Builder.Default
    private Integer reminderCount = 0;

    @Column(name = "last_reminder_at")
    private Instant lastReminderAt;

    /**
     * Soft-archive timestamp set by {@code clear-for-reoffer} so the
     * partial UNIQUE on (application_id) WHERE archived_at IS NULL still
     * allows a fresh active offer per application.
     */
    @Column(name = "archived_at")
    private Instant archivedAt;
}
