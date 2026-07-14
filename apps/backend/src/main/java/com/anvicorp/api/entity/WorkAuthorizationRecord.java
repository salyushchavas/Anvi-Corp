package com.anvicorp.api.entity;

import com.anvicorp.api.security.AesGcmCryptoConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * ERM Phase 5 — central work-authorization tracker. One row per user.
 * Backfilled from {@link Candidate#expectedTrack} + applicant_profile on
 * first Compliance Tracker load if missing.
 *
 * <p>{@code eadCardNumber} stored under
 * {@link AesGcmCryptoConverter} (GOVERNMENT_ID sensitivity).</p>
 */
@Entity
@Table(name = "work_authorization_records",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_work_auth_records_user", columnNames = "user_id"),
        indexes = {
                @Index(name = "idx_work_auth_records_type_expiration",
                        columnList = "work_auth_type, authorized_until")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkAuthorizationRecord {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    /** US_CITIZEN | PERMANENT_RESIDENT | F1_CPT | F1_OPT | F1_STEM_OPT | H1B | OTHER. */
    @Column(name = "work_auth_type", nullable = false, length = 40)
    private String workAuthType;

    @Column(name = "authorized_from")
    private LocalDate authorizedFrom;

    @Column(name = "authorized_until")
    private LocalDate authorizedUntil;

    /** AES-256-GCM envelope. Widened to TEXT to hold the encrypted blob. */
    @Column(name = "ead_card_number", columnDefinition = "TEXT")
    @Convert(converter = AesGcmCryptoConverter.class)
    private String eadCardNumber;

    @Column(name = "ead_expiration")
    private LocalDate eadExpiration;

    @Column(name = "i20_expiration")
    private LocalDate i20Expiration;

    @Column(name = "i983_required", nullable = false)
    @Builder.Default
    private Boolean i983Required = Boolean.FALSE;

    /** FK to intern_evaluations (or i983 plan) — nullable. */
    @Column(name = "i983_id")
    private UUID i983Id;

    @Column(name = "dso_name", length = 200)
    private String dsoName;

    @Column(name = "dso_email", length = 150)
    private String dsoEmail;

    @Column(name = "dso_phone", length = 30)
    private String dsoPhone;

    /** ERM-only — never returned to INTERN. */
    @Column(name = "erm_notes", columnDefinition = "TEXT")
    private String ermNotes;

    @UpdateTimestamp
    @Column(name = "last_updated_at", nullable = false)
    private Instant lastUpdatedAt;

    @Column(name = "last_updated_by_id", nullable = false)
    private UUID lastUpdatedById;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
