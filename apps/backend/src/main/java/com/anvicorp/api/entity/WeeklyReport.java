package com.anvicorp.api.entity;

import com.anvicorp.api.enums.WeeklyReportStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Weekly narrative report — the second piece of the Phase-2 weekly cycle.
 * Split out of {@link Timesheet#getDescription()}: the timesheet keeps the
 * hours + a freeform note for back-compat, while the structured narrative
 * (completed work / blockers / learnings / next plan) lives here going
 * forward.
 *
 * <h2>Pairing with the timesheet</h2>
 * The week key is {@code week_start: LocalDate} — same column type and
 * semantics as {@link Timesheet#getWeekStart()}, so a UI or report can join
 * the two by {@code (intern_id, week_start)}. We do NOT add an
 * {@code engagement_id} column on this entity (it's resolvable at read time
 * via the intern's active engagement) — keeps the row narrow and the
 * additive migration trivial.
 *
 * <h2>One report per intern per week</h2>
 * Enforced by the {@code uk_weekly_report_intern_week} unique constraint.
 * If an intern needs to log a second week, they create a new row keyed on
 * a different {@code week_start}.
 *
 * <h2>Lifecycle</h2>
 * Two-stage review (mirrors the timesheet flow):
 * {@code DRAFT → SUBMITTED → VERIFIED → APPROVED}, with {@code RETURNED}
 * as the send-back branch from either SUBMITTED (ERM stage) or VERIFIED
 * (Evaluator stage). APPROVED is terminal.
 */
@Entity
@Table(
        name = "weekly_reports",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_weekly_report_intern_week",
                columnNames = {"intern_id", "week_start"}
        ),
        indexes = {
                @Index(name = "idx_weekly_report_intern_status",
                        columnList = "intern_id, status"),
                @Index(name = "idx_weekly_report_week", columnList = "week_start"),
                @Index(name = "idx_weekly_report_reviewer", columnList = "reviewed_by")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WeeklyReport {

    @Id
    @GeneratedValue
    private UUID id;

    /** The intern (Candidate row). Same shape as Timesheet.intern. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "intern_id", nullable = false)
    private Candidate intern;

    /**
     * Pairs with {@link Timesheet#getWeekStart()}. A given intern has at most
     * one weekly report per {@code week_start}.
     */
    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @Column(name = "completed_work", columnDefinition = "TEXT")
    private String completedWork;

    @Column(columnDefinition = "TEXT")
    private String blockers;

    @Column(name = "learning_outcomes", columnDefinition = "TEXT")
    private String learningOutcomes;

    @Column(name = "next_plan", columnDefinition = "TEXT")
    private String nextPlan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private WeeklyReportStatus status = WeeklyReportStatus.DRAFT;

    /** Stamped on the DRAFT→SUBMITTED transition; re-stamped on resubmit. */
    @Column(name = "submitted_at")
    private Instant submittedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    /**
     * Required when a reviewer returns the report for correction;
     * optional when they approve. Stays populated across re-submits so the
     * intern can see what the reviewer last said.
     */
    @Column(name = "review_notes", columnDefinition = "TEXT")
    private String reviewNotes;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    /**
     * ERM who verified the SUBMITTED report. Stamped on the
     * SUBMITTED → VERIFIED transition; cleared on any RETURNED transition
     * so the response never carries stale attribution.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verified_by")
    private User verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    /**
     * Optional ERM verification note ("looks good, forwarding to Evaluator").
     * Distinct from {@link #reviewNotes}, which is the correction-notes channel
     * used by the RETURNED path. Kept so the Evaluator can see what the ERM
     * said when they picked up the row.
     */
    @Column(name = "erm_notes", columnDefinition = "TEXT")
    private String ermNotes;

    /**
     * Optional attachment — FK into {@code documents.id} for a supporting
     * file the intern uploaded (typically the scanned/exported PDF of the
     * week's work). Nullable — reports were text-only before this column,
     * and the file is still optional going forward.
     *
     * <p>Stored as a bare UUID (not a JPA relation) to match the
     * {@code Application.resume_id} / {@code Interview.recordingDocumentId}
     * convention in this repo — keeps the ddl-auto migration additive and
     * cross-package hard-delete cleanup simpler.</p>
     */
    @Column(name = "attachment_document_id")
    private UUID attachmentDocumentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
