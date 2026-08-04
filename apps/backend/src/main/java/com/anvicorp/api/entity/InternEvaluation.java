package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Phase 6 evaluation row. Lives at table {@code intern_evaluations} to avoid
 * colliding with the legacy {@code evaluations} table (DRAFT → FINALIZED
 * supervisor-author model). Phase 6's full lifecycle is DRAFT → SCHEDULED →
 * IN_PROGRESS → PUBLISHED → ACKNOWLEDGED → AMENDED.
 *
 * <p>Field-level RBAC: {@code internalNotes} + {@code zoomStartUrl} are
 * staff-only and never appear in intern-facing DTOs (controller chooses
 * the right shape).</p>
 */
@Entity
@Table(name = "intern_evaluations", indexes = {
        @Index(name = "idx_intern_eval_lifecycle_type",
                columnList = "intern_lifecycle_id, evaluation_type"),
        @Index(name = "idx_intern_eval_intern_status",
                columnList = "intern_id, status"),
        @Index(name = "idx_intern_eval_evaluator_status",
                columnList = "evaluator_id, status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InternEvaluation {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "intern_lifecycle_id", nullable = false)
    private UUID internLifecycleId;

    /** Denormalized for /mine queries. */
    @Column(name = "intern_id", nullable = false)
    private UUID internId;

    @Column(name = "evaluator_id", nullable = false)
    private UUID evaluatorId;

    /** MONTHLY | POST_PROJECT | STEM_OPT_12_MONTH | STEM_OPT_24_MONTH | FINAL. */
    @Column(name = "evaluation_type", nullable = false, length = 30)
    private String evaluationType;

    @Column(name = "linked_project_id")
    private UUID linkedProjectId;

    @Column(name = "linked_i983_id")
    private UUID linkedI983Id;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "scheduled_for")
    private Instant scheduledFor;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "timezone", length = 50)
    private String timezone;

    /**
     * Meeting provider id — String since Phase 2 of the WebEx migration
     * (column name kept for back-compat; type widened from BIGINT to
     * VARCHAR(64) by SchemaFixupRunner).
     */
    @Column(name = "zoom_meeting_id", length = 64)
    private String zoomMeetingId;

    @Column(name = "zoom_join_url", columnDefinition = "TEXT")
    private String zoomJoinUrl;

    /** Evaluator-only — never returned to interns. */
    @Column(name = "zoom_start_url", columnDefinition = "TEXT")
    private String zoomStartUrl;

    @Column(name = "zoom_password", length = 40)
    private String zoomPassword;

    /** DRAFT | SCHEDULED | IN_PROGRESS | PUBLISHED | ACKNOWLEDGED | AMENDED | CANCELLED. */
    @Column(name = "status", nullable = false, length = 20,
            columnDefinition = "varchar(20) not null default 'DRAFT'")
    @Builder.Default
    private String status = "DRAFT";

    @Column(name = "overall_score")
    private Integer overallScore;

    @Column(name = "technical_skills_score")
    private Integer technicalSkillsScore;

    @Column(name = "communication_score")
    private Integer communicationScore;

    @Column(name = "professionalism_score")
    private Integer professionalismScore;

    @Column(name = "learning_application_score")
    private Integer learningApplicationScore;

    @Column(name = "strengths_narrative", columnDefinition = "TEXT")
    private String strengthsNarrative;

    @Column(name = "areas_for_improvement_narrative", columnDefinition = "TEXT")
    private String areasForImprovementNarrative;

    @Column(name = "improvement_plan", columnDefinition = "TEXT")
    private String improvementPlan;

    @Column(name = "intern_acknowledged_at")
    private Instant internAcknowledgedAt;

    @Column(name = "intern_response", columnDefinition = "TEXT")
    private String internResponse;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "amended_at")
    private Instant amendedAt;

    @Column(name = "amendment_reason", columnDefinition = "TEXT")
    private String amendmentReason;

    @Column(name = "version", nullable = false,
            columnDefinition = "integer not null default 1")
    @Builder.Default
    private Integer version = 1;

    /** Evaluator Phase 2 — coarse summary recommendation.
     *  EXCELLENT | GOOD | SATISFACTORY | NEEDS_IMPROVEMENT | UNSATISFACTORY.
     *  Nullable for legacy rows + DRAFTs. */
    @Column(name = "recommendation", length = 32)
    private String recommendation;

    /** Evaluator-only — NEVER returned to interns. */
    @Column(name = "internal_notes", columnDefinition = "TEXT")
    private String internalNotes;

    /**
     * Optional S3-backed session recording. Bare UUID column (not a JPA
     * relation) matching the {@code Interview.recordingDocumentId} +
     * {@code ProjectSubmission.attachmentDocumentId} convention in this
     * repo — keeps the ddl-auto migration additive and cross-package
     * cleanup simpler. Video bytes are uploaded direct-to-S3 via a
     * presigned PUT; playback happens through a presigned GET. Null
     * until the evaluator attaches a recording.
     */
    @Column(name = "recording_document_id")
    private UUID recordingDocumentId;

    /**
     * Session grouping — evaluations scheduled together (bulkSchedule
     * covering multiple POST_PROJECT evaluations) share a single
     * {@code sessionGroupId} so the evaluator sees ONE start affordance,
     * ONE recording, and ONE cancel/reschedule surface for the whole
     * meeting. Single-project sessions get a group-of-one on schedule
     * (deterministic per-eval UUID). Null on legacy rows until the
     * SchemaFixupRunner backfill runs at boot.
     */
    @Column(name = "session_group_id")
    private UUID sessionGroupId;

    /**
     * Manager approval gate for the attached recording. Set to
     * {@code PENDING_APPROVAL} the moment a recording is saved on the
     * evaluation, then transitions to {@code APPROVED} or
     * {@code REVISION_REQUESTED} via the Manager approval queue. The
     * evaluator only sees their own recording once the status is
     * APPROVED — REVISION_REQUESTED surfaces the manager's notes back
     * to the evaluator and unlocks a re-upload (which resets the status
     * to PENDING_APPROVAL). Null until the first recording is attached.
     * DB-side CHECK constraint is rebuilt by SchemaFixupRunner.
     */
    @Column(name = "recording_approval_status", length = 32)
    private String recordingApprovalStatus;

    /** Manager's free-text notes captured when REVISION_REQUESTED — shown
     *  verbatim to the evaluator so they know what to fix on re-upload. */
    @Column(name = "recording_revision_notes", columnDefinition = "TEXT")
    private String recordingRevisionNotes;

    /** Manager who last transitioned the recording out of PENDING_APPROVAL. */
    @Column(name = "recording_approved_by")
    private UUID recordingApprovedBy;

    /** Timestamp of the last APPROVED / REVISION_REQUESTED transition. */
    @Column(name = "recording_approved_at")
    private Instant recordingApprovedAt;

    /**
     * "Recording scope" — which project(s) the attached recording
     * pertains to. Distinct from {@link #linkedProjectId} because the
     * evaluator may record ONE session that covers both P1 and P2
     * ("P1_P2"). Values: {@code P1}, {@code P2}, {@code P1_P2}. Null
     * until a recording is attached.
     */
    @Column(name = "recording_scope", length = 8)
    private String recordingScope;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
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
