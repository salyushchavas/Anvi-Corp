package com.anvicorp.api.evaluator.perproject;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * DTOs for the Evaluator per-project workflow — parallel to the monthly
 * evaluation cadence, keyed on {@code InternEvaluation.linkedProjectId +
 * evaluationType='POST_PROJECT'}.
 *
 * <p>The auto-draft flow already exists ({@code PostProjectEvaluationListener}
 * inserts a DRAFT POST_PROJECT eval on ProjectCompletedEvent). These
 * DTOs feed the evaluator's queue + Active Evaluee timeline + per-project
 * context hub that let them find + act on those rows.</p>
 */
public final class PerProjectDtos {

    private PerProjectDtos() {}

    // ── §1 Awaiting-evaluation queue ────────────────────────────────────

    /** One completed-project evaluation waiting on this evaluator. */
    public record AwaitingEvaluationRow(
            UUID evaluationId,
            String evaluationStatus,        // DRAFT | SCHEDULED | IN_PROGRESS
            UUID linkedProjectId,
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String employeeId,
            String projectTitle,
            String techStack,
            /** 1-based sequence: which project# for this intern (by
             *  {@code assignmentDate} then {@code createdAt}). */
            int projectSequence,
            LocalDate projectDueDate,
            Instant projectCompletedAt,
            Instant projectSubmittedAt,
            Instant evaluationScheduledFor,
            Long hoursWaitingSinceCompletion
    ) {}

    public record AwaitingEvaluationResponse(
            List<AwaitingEvaluationRow> items,
            int totalCount
    ) {}

    // ── §3 Intern project timeline ──────────────────────────────────────

    /** One project on an intern's timeline, with its evaluation state. */
    public record ProjectTimelineEntry(
            UUID projectId,
            int projectSequence,
            String title,
            String techStack,
            String projectStatus,           // ProjectStatus enum name
            LocalDate assignmentDate,
            LocalDate dueDate,
            Instant submittedAt,
            Instant completedAt,
            /** Non-null when a POST_PROJECT evaluation exists for this project. */
            UUID evaluationId,
            String evaluationStatus,        // DRAFT | SCHEDULED | IN_PROGRESS | PUBLISHED | ACKNOWLEDGED | AMENDED
            Instant evaluationPublishedAt,
            Integer evaluationOverallScore,
            String evaluationRecommendation,
            /** Derived UI state for the timeline chip. */
            String uiState                  // IN_PROGRESS | AWAITING_EVAL | EVALUATED | AMENDED
    ) {}

    public record ProjectTimelineResponse(
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String employeeId,
            int totalProjects,
            int evaluatedCount,
            int awaitingEvaluationCount,
            int inProgressCount,
            List<ProjectTimelineEntry> entries
    ) {}

    // ── §4 Per-project context — project detail for the hub ─────────────

    public record ProjectContext(
            UUID projectId,
            String title,
            String description,
            String deliverables,
            String requirements,
            String objectives,
            String instructions,
            String techStack,
            String difficulty,
            Integer projectNumber,
            String monthYear,
            String status,
            LocalDate startDate,
            LocalDate dueDate,
            Instant startedAt,
            Instant submittedAt,
            Instant completedAt,
            /** Trainer's review notes captured on the project row. */
            String reviewNotes,
            String reviewerName
    ) {}

    // ── §4 Per-project context — recent weekly reports ──────────────────

    public record WeeklyReportSummary(
            UUID reportId,
            LocalDate weekStart,
            String status,                  // DRAFT | SUBMITTED | VERIFIED | APPROVED | RETURNED
            Instant submittedAt,
            /** Timestamp of the most recent reviewer action (verify /
             *  approve / return). Mapped from {@code WeeklyReport.reviewedAt}. */
            Instant reviewedAt,
            /** Intern's completed-work narrative for the week. */
            String completedWork,
            String blockers
    ) {}

    public record WeeklyReportsResponse(
            UUID internUserId,
            List<WeeklyReportSummary> items
    ) {}

    // ── §5 Per-project schedule request ─────────────────────────────────

    /**
     * Schedule a session for an existing POST_PROJECT evaluation row.
     * Reuses Build-1 meeting_timezone conventions — {@code scheduledFor}
     * is a UTC Instant already converted from wall-clock in the picked
     * zone by the frontend.
     */
    public record SchedulePostProjectRequest(
            @NotNull Instant scheduledFor,
            Integer durationMinutes,
            String timezone,
            String topic,
            String agenda
    ) {}
}
