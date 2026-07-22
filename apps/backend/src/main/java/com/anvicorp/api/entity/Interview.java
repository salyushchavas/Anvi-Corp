package com.anvicorp.api.entity;

import com.anvicorp.api.enums.InterviewRecommendation;
import com.anvicorp.api.enums.InterviewStatus;
import com.anvicorp.api.enums.InterviewType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "interviews",
        indexes = {
                @Index(name = "idx_interview_application", columnList = "application_id"),
                @Index(name = "idx_interview_interviewer_scheduled", columnList = "interviewer_id, scheduled_at"),
                @Index(name = "idx_interview_status_scheduled", columnList = "status, scheduled_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Interview {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "interviewer_id", nullable = false)
    private User interviewer;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "duration_minutes", nullable = false)
    @Builder.Default
    private Integer durationMinutes = 60;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InterviewType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private InterviewStatus status = InterviewStatus.SCHEDULED;

    @Column(name = "meeting_url", length = 500)
    private String meetingUrl;

    @Column(name = "candidate_notes", columnDefinition = "TEXT")
    private String candidateNotes;

    // ── Phase 2: Zoom + applicant-safe outcome fields ───────────────────────
    //
    // zoom_start_url is HOST-ONLY — never returned to applicants. See the
    // applicant-safe DTO mapper in InterviewController.

    /** IANA timezone (e.g. "America/New_York"). Defaults to UTC. */
    @Column(name = "timezone", nullable = false, length = 50,
            columnDefinition = "varchar(50) not null default 'UTC'")
    @Builder.Default
    private String timezone = "UTC";

    /**
     * Meeting provider id (null when no provider is enabled or the create
     * call failed). String-typed since Phase 2 of the WebEx migration —
     * Zoom returns numeric ids stored as decimal strings; WebEx returns
     * opaque alphanumeric ids. Column name kept as {@code zoom_meeting_id}
     * for back-compat; the column TYPE was widened from BIGINT to
     * VARCHAR(64) by SchemaFixupRunner.
     */
    @Column(name = "zoom_meeting_id", length = 64)
    private String zoomMeetingId;

    /** Applicant-safe Zoom join URL. */
    @Column(name = "zoom_join_url", columnDefinition = "TEXT")
    private String zoomJoinUrl;

    /** HOST-ONLY Zoom start URL — never serialised to applicants. */
    @Column(name = "zoom_start_url", columnDefinition = "TEXT")
    private String zoomStartUrl;

    @Column(name = "zoom_password", length = 40)
    private String zoomPassword;

    /** SELECTED | HOLD | REJECTED. Set on /complete; null before. */
    @Column(name = "decision", length = 20)
    private String decision;

    /** Doc-spec outcome message shown to the applicant. ≥ 20 chars on complete. */
    @Column(name = "applicant_visible_notes", columnDefinition = "TEXT")
    private String applicantVisibleNotes;

    /** ERM/manager-only notes — NEVER returned to the applicant. */
    @Column(name = "internal_notes", columnDefinition = "TEXT")
    private String internalNotes;

    /** Prep instructions shown to the applicant on the Interview Center hero card. */
    @Column(name = "prep_instructions", columnDefinition = "TEXT")
    private String prepInstructions;

    @Column(name = "feedback_overall_rating")
    private Integer feedbackOverallRating;

    @Column(name = "feedback_technical_rating")
    private Integer feedbackTechnicalRating;

    @Column(name = "feedback_communication_rating")
    private Integer feedbackCommunicationRating;

    /**
     * Phase 2.2 — third dimension in the structured scorecard. 1-5; null when the
     * interviewer skipped it (legacy /feedback rows or before the column existed).
     */
    @Column(name = "feedback_problem_solving_rating")
    private Integer feedbackProblemSolvingRating;

    @Column(name = "feedback_strengths", columnDefinition = "TEXT")
    private String feedbackStrengths;

    @Column(name = "feedback_concerns", columnDefinition = "TEXT")
    private String feedbackConcerns;

    /**
     * Phase 2.2 — unified scorecard comments. The legacy strengths/concerns
     * pair is preserved so existing rows keep rendering; the new /scorecard
     * path writes to this single column.
     */
    @Column(name = "feedback_comments", columnDefinition = "TEXT")
    private String feedbackComments;

    @Enumerated(EnumType.STRING)
    @Column(name = "feedback_recommendation")
    private InterviewRecommendation feedbackRecommendation;

    @Column(name = "feedback_submitted_at")
    private Instant feedbackSubmittedAt;

    @Column(name = "feedback_submitted_by")
    private UUID feedbackSubmittedBy;

    // ── ERM Phase 3 — scheduler + decision center columns ──────────────────

    /** Optional list of panel interviewer user IDs (JSON-serialized UUIDs). */
    @Column(name = "panel_interviewer_ids", columnDefinition = "TEXT")
    private String panelInterviewerIdsJson;

    @Column(name = "reschedule_count", nullable = false,
            columnDefinition = "integer not null default 0")
    @Builder.Default
    private Integer rescheduleCount = 0;

    @Column(name = "last_reschedule_reason_code", length = 80)
    private String lastRescheduleReasonCode;

    @Column(name = "last_reschedule_reason_text", columnDefinition = "TEXT")
    private String lastRescheduleReasonText;

    @Column(name = "last_rescheduled_at")
    private Instant lastRescheduledAt;

    @Column(name = "last_rescheduled_by_id")
    private UUID lastRescheduledById;

    @Column(name = "cancellation_reason_code", length = 80)
    private String cancellationReasonCode;

    @Column(name = "cancellation_reason_text", columnDefinition = "TEXT")
    private String cancellationReasonText;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by_id")
    private UUID cancelledById;

    /** ERM Phase 3 — 1-10 rubric, ERM/Manager-visible only. */
    @Column(name = "technical_score")
    private Integer technicalScore;

    @Column(name = "communication_score")
    private Integer communicationScore;

    @Column(name = "cultural_fit_score")
    private Integer culturalFitScore;

    /** STRONG_HIRE | HIRE | NO_HIRE | STRONG_NO_HIRE. */
    @Column(name = "overall_recommendation", length = 20)
    private String overallRecommendation;

    @Column(name = "decision_reason_code", length = 80)
    private String decisionReasonCode;

    /** ERM-only — never returned to INTERN. */
    @Column(name = "decision_reason_text", columnDefinition = "TEXT")
    private String decisionReasonText;

    /**
     * Manager hire-approval gate. The ERM submits the scorecard but no
     * longer sets {@link #decision}; instead the row enters
     * {@code PENDING} on /complete, and a Manager flips it to
     * {@code APPROVED} (which also sets {@code decision = SELECTED} so
     * the existing selection-ack + offer chain proceeds) or
     * {@code REJECTED}. Null on historical rows that pre-date the gate.
     */
    @Column(name = "manager_hire_decision", length = 20)
    private String managerHireDecision;

    @Column(name = "manager_hire_decision_at")
    private Instant managerHireDecisionAt;

    @Column(name = "manager_hire_decision_by_id")
    private UUID managerHireDecisionById;

    /** Optional rationale recorded by the manager on approve/reject. */
    @Column(name = "manager_hire_decision_note", columnDefinition = "TEXT")
    private String managerHireDecisionNote;

    /**
     * FK into {@code documents.id} for the ERM-uploaded interview recording
     * (mp4 / mov / webm). Nullable — most interviews will not have a
     * recording attached. Populated by
     * {@code ErmInterviewService.complete} when the ERM includes
     * {@code recordingDocumentId} on the scorecard submit; the referenced
     * {@link com.anvicorp.api.entity.Document} row was created earlier by
     * the presign-upload endpoint and its bytes uploaded directly to S3
     * (browser → S3, presigned PUT).
     *
     * <p>Kept as a plain UUID column (not a JPA relation) to match the
     * {@code Application.resume_id}-style convention already used in this
     * repo — makes the DDL migration and hard-delete cleanup simpler.</p>
     */
    @Column(name = "recording_document_id")
    private UUID recordingDocumentId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
