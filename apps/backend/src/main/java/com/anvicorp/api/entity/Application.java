package com.anvicorp.api.entity;

import com.anvicorp.api.enums.ApplicationStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
// NOTE: no @UniqueConstraint on (candidate_id, job_posting_id) here — an
// intern who WITHDREW must be able to re-apply, so the "one active
// application per (candidate, posting)" rule is enforced by a Postgres
// PARTIAL UNIQUE index maintained by
// SchemaFixupRunner.ensureApplicationsReapplyAfterWithdrawSchema (name:
// uk_application_candidate_job_posting_active, WHERE status <> 'WITHDRAWN').
// Hibernate ddl-auto=update can't express partial indexes, so we keep the
// entity constraint out of its way and treat the runner as the source of
// truth. The runner also drops the legacy unconditional
// uk_application_candidate_job_posting so old databases converge.
@Table(name = "applications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Application {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false)
    private Candidate candidate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_posting_id", nullable = false)
    private JobPosting jobPosting;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id")
    private Resume resume;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ApplicationStatus status = ApplicationStatus.APPLIED;

    @Column(nullable = false, updatable = false)
    private Instant appliedAt;

    @Column(nullable = false)
    private Instant statusUpdatedAt;

    @Column(name = "status_updated_by")
    private UUID statusUpdatedBy;

    @Column(name = "recruiter_notes", columnDefinition = "TEXT")
    private String recruiterNotes;

    /** Recruiter's 1-5 rating from the review screen. Nullable. */
    @Column(name = "recruiter_rating")
    private Integer recruiterRating;

    /** Phase 2: applicant-typed motivation captured at apply time. ≤ 500 chars. */
    @Column(name = "statement_of_interest", columnDefinition = "TEXT")
    private String statementOfInterest;

    /**
     * Phase 2: applicant-safe feedback string set by ERM at SHORTLIST/REJECT/
     * interview-complete. Surfaced verbatim on the applicant's My Applications
     * detail page when stage is INTERVIEW_COMPLETED. Internal notes live on
     * {@code recruiter_notes} / {@code interviews.internal_notes} — never here.
     */
    @Column(name = "applicant_visible_feedback", columnDefinition = "TEXT")
    private String applicantVisibleFeedback;

    /** ERM owner who shortlisted or last touched the application. Nullable. */
    @Column(name = "erm_owner_id")
    private UUID ermOwnerId;

    // ── ERM Phase 2 — decision-flow context ────────────────────────────────
    // Captures the most recent ERM decision (HOLD / REQUEST_INFO / REJECT /
    // SHORTLIST) so the inbox row can render reason chips without joining
    // application_decision_logs. Full immutable history lives on that table.

    /** Most recent {@code ReasonCode} value, e.g. {@code REJECT_NO_WORK_AUTH}. */
    @Column(name = "last_decision_reason_code", length = 80)
    private String lastDecisionReasonCode;

    /**
     * Free text accompanying the decision. For HOLD/REJECT this stays
     * ERM-internal (the applicant sees only the reason label). For
     * REQUEST_INFO the text is the ERM's specific ask — surfaced verbatim
     * to the applicant since REQUEST_INFO_OTHER's "(specify)" prompt is
     * explicitly the field where the ERM types what they need. The DTO
     * mapper gates this exposure on stage == INFO_REQUESTED.
     */
    @Column(name = "last_decision_reason_text", columnDefinition = "TEXT")
    private String lastDecisionReasonText;

    @Column(name = "last_decision_at")
    private Instant lastDecisionAt;

    @Column(name = "last_decision_by_id")
    private UUID lastDecisionById;

    /** CSV of fields the applicant must update (resume,workAuth,education,other). */
    @Column(name = "info_requested_fields_csv", length = 500)
    private String infoRequestedFieldsCsv;

    @Column(name = "info_requested_at")
    private Instant infoRequestedAt;

    /**
     * Applicant's free-text response captured when they close the
     * INFO_REQUESTED loop via POST /provide-info. Persisted so the
     * exchange is visible to both sides after the status flips back to
     * APPLIED. Not cleared on subsequent decisions — the applicant + ERM
     * can always see what was asked and what was answered on the
     * detail page's history section.
     */
    @Column(name = "info_provided_response", columnDefinition = "TEXT")
    private String infoProvidedResponse;

    @Column(name = "info_provided_at")
    private Instant infoProvidedAt;

    /**
     * Free-form ERM-only notes appended over time. Distinct from
     * {@code recruiter_notes} (legacy single-field) — internal_notes is the
     * doc-spec'd Phase 2 notes column that the detail tab edits.
     */
    @Column(name = "internal_notes", columnDefinition = "TEXT")
    private String internalNotes;

    /**
     * Set when the intern clicks "Receive my offer letter" on their
     * dashboard after the ERM marks them SELECTED. Gates
     * {@code ErmOfferService.createAndSend} — an offer cannot be issued
     * until the intern has acknowledged selection. Null until ack.
     */
    @Column(name = "selection_acknowledged_at")
    private Instant selectionAcknowledgedAt;

    /** UUID of the intern user who acknowledged selection (== candidate.user.id). */
    @Column(name = "selection_acknowledged_by")
    private UUID selectionAcknowledgedBy;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.appliedAt = now;
        this.statusUpdatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.statusUpdatedAt = Instant.now();
    }
}
