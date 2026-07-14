package com.anvicorp.api.entity;

import com.anvicorp.api.enums.EVerifyClosureReason;
import com.anvicorp.api.enums.EVerifyStatus;
import com.anvicorp.api.enums.PhotoMatchResult;
import com.anvicorp.api.security.AesGcmCryptoConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
        name = "everify_cases",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_everify_i9_form",
                columnNames = "i9_form_id"
        ),
        indexes = {
                @Index(name = "idx_everify_status", columnList = "status")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EVerifyCase {

    @Id
    @GeneratedValue
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "i9_form_id", nullable = false, unique = true)
    private I9Form i9Form;

    /**
     * AES-256-GCM envelope. ERM-entered case number from manual E-Verify
     * session. Widened to TEXT to hold the encrypted blob. Masked in UI
     * (E••••1234) — full reveal requires click-through with audit log.
     */
    @Column(name = "case_number", columnDefinition = "TEXT")
    @Convert(converter = AesGcmCryptoConverter.class)
    private String caseNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private EVerifyStatus status = EVerifyStatus.PENDING_SUBMISSION;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /**
     * Phase 3 step 7 — federal deadline: E-Verify case must be opened by the
     * 3rd business day after the employee's first day. Computed at create time
     * from the linked I-9's engagement (actualStartDate ?? plannedStartDate),
     * with the I-9's firstDayOfEmployment as legacy fallback. Null when no
     * start date is known yet.
     */
    @Column(name = "due_by")
    private LocalDate dueBy;

    /**
     * Phase 3 step 7 — placeholder for future E-Verify API integration:
     * timestamp of the last successful poll/webhook sync with the federal
     * E-Verify service. Stays null until real integration ships (Sprint 4+).
     */
    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "closure_reason")
    private EVerifyClosureReason closureReason;

    @Column(name = "photo_match_required", nullable = false)
    @Builder.Default
    private Boolean photoMatchRequired = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "photo_match_result")
    private PhotoMatchResult photoMatchResult;

    @Column(name = "additional_verification_required", nullable = false)
    @Builder.Default
    private Boolean additionalVerificationRequired = false;

    @Column(columnDefinition = "TEXT")
    private String notes;

    /** ERM-only — never returned to INTERN. */
    @Column(name = "erm_notes", columnDefinition = "TEXT")
    private String ermNotes;

    /**
     * ERM-set business-day deadline (typically dueBy + 10 business days for
     * TNC contests). Drives compliance alerts when status is still
     * TENTATIVE_NONCONFIRMATION near or past this date.
     */
    @Column(name = "expected_close_by")
    private LocalDate expectedCloseBy;

    @Column(name = "last_updated_at")
    private Instant lastUpdatedAt;

    @Column(name = "last_updated_by_id")
    private UUID lastUpdatedById;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;
}
