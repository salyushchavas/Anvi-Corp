package com.anvicorp.api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per {@code /submit} on a {@link Project}. Keeps the resubmission
 * history so the supervisor can see what changed between rounds and so the
 * intern can re-read what they sent last time after a RETURNED bounce.
 */
@Entity
@Table(
        name = "project_submissions",
        indexes = {
                @Index(name = "idx_project_submission_project",
                        columnList = "project_id, submitted_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectSubmission {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /** Intern's note for this submission — what they did this round. */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** JSON-encoded {@code List<String>} of deliverable links (PR, deploy URL, docs). */
    @Column(name = "links_json", columnDefinition = "TEXT")
    private String linksJson;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    // ── Trainer Phase 0 — doc Feedback Form columns ────────────────────────
    // Phase 3 will populate these via the dedicated review service; Phase 0
    // just adds the columns so the schema is doc-compliant.

    /** Doc Feedback Form "Technical quality score" — 1-5. */
    @Column(name = "technical_score")
    private Short technicalScore;

    /** Doc Feedback Form "Communication / clarity" — 1-5. */
    @Column(name = "communication_score")
    private Short communicationScore;

    /** Doc Feedback Form "Blockers" textarea. */
    @Column(name = "blockers_note", columnDefinition = "TEXT")
    private String blockersNote;

    /** Doc Feedback Form "Next action" dropdown —
     *  REVISION | NEXT_PROJECT | EXTRA_TRAINING | ESCALATION. */
    @Column(name = "next_action", length = 40)
    private String nextAction;

    @Column(name = "next_action_due_date")
    private java.time.LocalDate nextActionDueDate;

    /** Doc Feedback Form "Attachments / reviewed links" — external URLs
     *  (e.g. GitHub PR links). Internal document ids go through the
     *  existing attached_document_ids JSONB column added in Phase 5. */
    @Column(name = "reviewed_links_csv", columnDefinition = "TEXT")
    private String reviewedLinksCsv;

    // ── Trainer Phase 3 — review state + decision capture ──────────────────

    /** Resubmission counter — 1 for the first submission on a project,
     *  2 for the next round after a REQUEST_REVISION, etc. */
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 1;

    /** Trainer's terminal decision on this round:
     *  ACCEPT | REQUEST_REVISION | ESCALATE | NO_ACTION_YET. Null while the
     *  row sits in the Pending Reviews queue. */
    @Column(name = "trainer_decision", length = 20)
    private String trainerDecision;

    /** Doc §9 trainer feedback — shown verbatim to the intern on
     *  REQUEST_REVISION / ESCALATE. Distinct from {@link #blockersNote}
     *  (which the intern fills) and {@link #reviewedLinksCsv}. */
    @Column(name = "trainer_feedback", columnDefinition = "TEXT")
    private String trainerFeedback;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewed_by_id")
    private UUID reviewedById;

    /** Optional doc Feedback Form "Project completion status" — captured for
     *  audit / reporting even when the trainer's terminal decision is
     *  ESCALATE / NO_ACTION_YET. */
    @Column(name = "completion_status", length = 24)
    private String completionStatus;

    /**
     * Optional attachment — FK into {@code documents.id} for a supporting
     * file the intern uploaded alongside deliverable links (typically the
     * built deployable, a design brief, or a supporting doc). Nullable —
     * submissions were link-only before this column, and the file is
     * still optional going forward.
     *
     * <p>Bare UUID column (not a JPA relation) to match the
     * {@code Interview.recordingDocumentId} / {@code WeeklyReport.attachmentDocumentId}
     * convention in this repo — keeps the ddl-auto migration additive
     * and cross-package hard-delete cleanup simpler.</p>
     */
    @Column(name = "attachment_document_id")
    private UUID attachmentDocumentId;

    @PrePersist
    void onCreate() {
        if (submittedAt == null) submittedAt = Instant.now();
        if (version == null) version = 1;
    }
}
