package com.anvicorp.api.entity;

import com.anvicorp.api.enums.CitizenshipStatus;
import com.anvicorp.api.enums.I9Status;
import com.anvicorp.api.security.AesGcmCryptoConverter;
import com.anvicorp.api.security.EncryptedLocalDateConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
        name = "i9_forms",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_i9_candidate",
                columnNames = "candidate_id"
        ),
        indexes = {
                @Index(name = "idx_i9_status", columnList = "status"),
                @Index(name = "idx_i9_first_day", columnList = "first_day_of_employment")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class I9Form {

    // ── Identity ────────────────────────────────────────────────────────────

    @Id
    @GeneratedValue
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false, unique = true)
    private Candidate candidate;

    /**
     * Phase 3 step 5 — link to the {@link Engagement} this I-9 belongs to.
     * Nullable: legacy I-9 rows pre-date Engagement and stay candidate-keyed.
     * New rows (created via {@code I9FormService.createForCandidate}) resolve
     * the candidate's most-recent in-funnel engagement and set this FK.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "engagement_id")
    private Engagement engagement;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private I9Status status = I9Status.NOT_STARTED;

    // ── Deadlines (Phase 3 step 5) ──────────────────────────────────────────

    /**
     * When the candidate must complete Section 1 by. Equals the first day of
     * employment per the I-9 regulation. Backfilled at create time from the
     * accepted offer / engagement start date.
     */
    @Column(name = "section_1_due_date")
    private java.time.LocalDate section1DueDate;

    /**
     * When HR must complete Section 2 by. Equals 3 business days after the
     * first day of employment. Recomputed when {@code firstDayOfEmployment}
     * is set/changed during Section 2 entry.
     */
    @Column(name = "section_2_due_date")
    private java.time.LocalDate section2DueDate;

    // ── Section 1 ───────────────────────────────────────────────────────────

    @Column(length = 80)
    private String lastName;

    @Column(length = 80)
    private String firstName;

    @Column(length = 4)
    private String middleInitial;

    @Column(length = 200)
    private String otherLastNamesUsed;

    @Column(length = 200)
    private String addressStreet;

    @Column(length = 20)
    private String addressAptNumber;

    @Column(length = 80)
    private String addressCity;

    @Column(length = 4)
    private String addressState;

    @Column(length = 10)
    private String addressZipCode;

    // GAP C7 — dateOfBirth is PII; encrypted at rest via the converter below.
    // SchemaFixupRunner widens the DB column from DATE to TEXT to hold the
    // base64(IV||ciphertext+tag) envelope.
    @Column(name = "date_of_birth", columnDefinition = "TEXT")
    @Convert(converter = EncryptedLocalDateConverter.class)
    private LocalDate dateOfBirth;

    /**
     * SSN in XXX-XX-XXXX format. GAP C7: encrypted at rest (AES-256-GCM).
     * Column widened to TEXT to hold the base64(IV||ciphertext+tag) envelope.
     * Never log this field.
     */
    @Column(columnDefinition = "TEXT")
    @Convert(converter = AesGcmCryptoConverter.class)
    private String ssn;

    @Column(length = 120)
    private String email;

    @Column(length = 20)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "citizenship_status")
    private CitizenshipStatus citizenshipStatus;

    // GAP C7 — A-Number is PII. Encrypted at rest; column widened to TEXT.
    @Column(columnDefinition = "TEXT")
    @Convert(converter = AesGcmCryptoConverter.class)
    private String alienRegistrationNumber;

    // GAP C7 — foreign passport number is a sensitive document number.
    // Encrypted at rest; column widened to TEXT.
    @Column(columnDefinition = "TEXT")
    @Convert(converter = AesGcmCryptoConverter.class)
    private String foreignPassportNumber;

    @Column(length = 80)
    private String foreignPassportCountry;

    @Column(name = "work_auth_expiration_date")
    private LocalDate workAuthExpirationDate;

    @Column(name = "preparer_translator_used", nullable = false)
    @Builder.Default
    private Boolean preparerTranslatorUsed = false;

    @Column(name = "section1_signed_at")
    private Instant section1SignedAt;

    @Column(name = "section1_signed_by_name", length = 160)
    private String section1SignedByName;

    // ── Section 2 ───────────────────────────────────────────────────────────

    @Column(name = "first_day_of_employment")
    private LocalDate firstDayOfEmployment;

    @Column(name = "list_a_title", length = 80)
    private String listATitle;

    @Column(name = "list_a_issuing_authority", length = 120)
    private String listAIssuingAuthority;

    // GAP C7 — List A document number is PII. Encrypted at rest.
    @Column(name = "list_a_document_number", columnDefinition = "TEXT")
    @Convert(converter = AesGcmCryptoConverter.class)
    private String listADocumentNumber;

    @Column(name = "list_a_expiration_date")
    private LocalDate listAExpirationDate;

    @Column(name = "list_b_title", length = 80)
    private String listBTitle;

    @Column(name = "list_b_issuing_authority", length = 120)
    private String listBIssuingAuthority;

    // GAP C7 — List B document number is PII. Encrypted at rest.
    @Column(name = "list_b_document_number", columnDefinition = "TEXT")
    @Convert(converter = AesGcmCryptoConverter.class)
    private String listBDocumentNumber;

    @Column(name = "list_b_expiration_date")
    private LocalDate listBExpirationDate;

    @Column(name = "list_c_title", length = 80)
    private String listCTitle;

    @Column(name = "list_c_issuing_authority", length = 120)
    private String listCIssuingAuthority;

    // GAP C7 — List C document number is PII. Encrypted at rest.
    @Column(name = "list_c_document_number", columnDefinition = "TEXT")
    @Convert(converter = AesGcmCryptoConverter.class)
    private String listCDocumentNumber;

    @Column(name = "additional_information", columnDefinition = "TEXT")
    private String additionalInformation;

    @Column(name = "employer_name", length = 160)
    private String employerName;

    @Column(name = "employer_title", length = 120)
    private String employerTitle;

    @Column(name = "business_organization_name", length = 200)
    private String businessOrganizationName;

    @Column(name = "business_address", columnDefinition = "TEXT")
    private String businessAddress;

    @Column(name = "section2_signed_at")
    private Instant section2SignedAt;

    @Column(name = "section2_signed_by_user_id")
    private UUID section2SignedByUserId;

    // ── Section 3 (reserved for v2) ─────────────────────────────────────────

    @Column(name = "section3_signed_at")
    private Instant section3SignedAt;

    @Column(name = "section3_signed_by_user_id")
    private UUID section3SignedByUserId;

    // ── Audit fields ────────────────────────────────────────────────────────

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;
}
