package com.anvicorp.api.entity;

import com.anvicorp.api.enums.WorkAuthTrack;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "candidates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Candidate {

    @Id
    @GeneratedValue
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    private LocalDate dateOfBirth;

    @Column(name = "default_resume_id")
    private UUID defaultResumeId;

    /**
     * Technical Evaluator assigned to this intern's biweekly evaluation sessions
     * (Group C — Supervised Work). Nullable: candidates are assigned an evaluator
     * after they reach HIRED status.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_evaluator_id")
    private User assignedEvaluator;

    // ── Phase 1.4 intake profile ────────────────────────────────────────────
    // All new columns are nullable so existing demo + production rows backfill
    // cleanly under ddl-auto=update. The candidate's display name still lives
    // on User.fullName; legalName/preferredName ride alongside for compliance
    // and addressing (e.g. an offer letter uses legalName when present).

    @Column(name = "legal_name", length = 200)
    private String legalName;

    @Column(name = "preferred_name", length = 200)
    private String preferredName;

    /**
     * Legacy free-form education summary (pre-Phase 1.5). New registrations
     * write the structured {@link #degreeLevel} / {@link #specialization} /
     * {@link #graduationYear} columns instead; this field is kept so old rows
     * still render in the ERM detail view rather than going blank.
     */
    @Column(length = 500)
    private String education;

    @Column(length = 200)
    private String school;

    /** Legacy free-text degree (pre-Phase 1.5). New rows leave this null
     *  and write {@link #degreeLevel} instead. */
    @Column(length = 200)
    private String degree;

    /** Structured education level — replaces free-text {@link #degree} for
     *  new registrations. Nullable on legacy rows. */
    @Enumerated(EnumType.STRING)
    @Column(name = "degree_level", length = 20)
    private com.anvicorp.api.enums.DegreeLevel degreeLevel;

    /** Free-text major / concentration (e.g. "Computer Science",
     *  "M.S. Quantitative Finance"). Intentionally open — domain-specific
     *  programs don't fit a dropdown. */
    @Column(name = "specialization", length = 200)
    private String specialization;

    /** Expected or actual graduation year. Bounded by the registration
     *  form to {@code currentYear-30 .. currentYear+8}. */
    @Column(name = "graduation_year")
    private Short graduationYear;

    /** Comma-separated or freeform — recruiter-readable. */
    @Column(columnDefinition = "TEXT")
    private String skillset;

    // ── Phase 1.4 neutral work-authorization self-attestation ───────────────
    // HARD compliance rule (PRODUCT.md §5/§8): take ONLY the candidate's
    // neutral self-attestation here. NO documents (I-9 / E-Verify) at this
    // stage — those are post-offer (Phase 3). Do NOT use these fields to
    // gate application access or eligibility; they drive compliance routing
    // only.

    @Column(name = "authorized_to_work")
    private Boolean authorizedToWork;

    @Column(name = "sponsorship_needed")
    private Boolean sponsorshipNeeded;

    @Enumerated(EnumType.STRING)
    @Column(name = "expected_track", length = 16)
    private WorkAuthTrack expectedTrack;

    /**
     * Self-disclosed END / expiration date of the work authorization. Null
     * when not applicable to the chosen track (per
     * {@link com.anvicorp.api.enums.VisaDateRequirement}) or when the
     * candidate declines to disclose.
     */
    @Column(name = "validity_date")
    private LocalDate validityDate;

    /**
     * Self-disclosed START date of the work authorization. Only collected
     * for {@link com.anvicorp.api.enums.WorkAuthTrack#OTHER} per
     * {@link com.anvicorp.api.enums.VisaDateRequirement#BOTH}; null
     * otherwise.
     */
    @Column(name = "validity_start_date")
    private LocalDate validityStartDate;

    // ── B2 US address (self-service) ────────────────────────────────────────
    // Full US postal address collected on the profile Address section. All
    // nullable so pre-B2 rows keep rendering; server-side validation lives
    // in UserProfileService (ZIP regex + 50-state whitelist mirror the
    // client). country stored as ISO-2, defaulted 'US' by the DB DEFAULT.

    @Column(name = "address_street", length = 200)
    private String addressStreet;

    @Column(name = "address_apt", length = 60)
    private String addressApt;

    @Column(name = "address_city", length = 120)
    private String addressCity;

    /** 2-letter US state code (or 'DC'). Uppercase on write. */
    @Column(name = "address_state", length = 2)
    private String addressState;

    /** 5-digit or 5+4 ZIP as a plain string. */
    @Column(name = "address_zip", length = 10)
    private String addressZip;

    /** ISO-2 country code — column default is 'US'. */
    @Column(name = "address_country", length = 2)
    private String addressCountry;

    /**
     * One-shot stamp fired the first time the profile satisfies the stricter
     * submission bar (base fields + address + at least one education). Null
     * on legacy rows and on candidates who haven't yet met the stricter bar;
     * the stamp gates the ERM submission-ack email/in-app so it fires
     * exactly once per intern.
     */
    @Column(name = "profile_submitted_at")
    private Instant profileSubmittedAt;

    /**
     * Throttle guard for the post-submission edit notification — at most
     * one email per intern per 15 minutes. Rapid edits collapse into one
     * notification round; the next round only fires once this timestamp
     * plus 15 minutes has passed. Null on legacy rows / rows that never
     * emitted an edit notification.
     */
    @Column(name = "last_profile_notified_at")
    private Instant lastProfileNotifiedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
