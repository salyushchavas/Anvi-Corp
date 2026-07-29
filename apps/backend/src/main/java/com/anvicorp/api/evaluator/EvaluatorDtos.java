package com.anvicorp.api.evaluator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Evaluator Phase 1 — DTO surface for the four read-only services:
 * {@link EvaluatorDashboardService} (Home KPIs),
 * {@link EvaluatorEvalueesService} (Active Evaluees list + detail),
 * {@link EvaluatorRightPanelService} (right-side panel context).
 *
 * <p>All records are immutable + Jackson-friendly. No service business
 * logic lives here.</p>
 */
public final class EvaluatorDtos {

    private EvaluatorDtos() {}

    // ── Home / Dashboard ───────────────────────────────────────────────────

    public record DashboardResponse(
            CallerView caller,
            String monthYearLabel,
            List<KpiSnapshot> kpis
    ) {}

    public record CallerView(
            UUID userId,
            String fullName,
            String email
    ) {}

    public record KpiSnapshot(
            String key,
            String label,
            long count,
            long urgentCount,
            String helperText,
            String actionUrl
    ) {}

    // ── Active Evaluees list ──────────────────────────────────────────────

    /** Per-project summary embedded in each ActiveEvalueeRow so the list
     *  can render Project 1 / Project 2 status + evaluation columns
     *  without an N+1 fetch. Sequence is 1-based, capped at 2 by DB CHECK. */
    public record ActiveEvalueeProjectRow(
            int sequence,
            String projectStatus,
            UUID evaluationId,
            String evaluationStatus
    ) {}

    public record ActiveEvalueeRow(
            UUID lifecycleId,
            UUID internUserId,
            String internName,
            String employeeId,
            String technology,
            String workAuthType,
            Instant startedAt,
            int monthsInProgram,
            Instant lastEvaluationAt,
            String lastEvaluationStatus,
            String lastEvaluationType,
            int pendingAckCount,
            Integer i983DueWithinDays,
            String trainerName,
            String ermName,
            List<ActiveEvalueeProjectRow> projects
    ) {}

    public record ActiveEvalueesPage(
            List<ActiveEvalueeRow> items,
            int page,
            int pageSize,
            long totalElements,
            int totalPages
    ) {}

    // ── Evaluee detail ────────────────────────────────────────────────────

    public record EvalueeDetail(
            EvalueeProfile profile,
            CurrentMonthCard currentMonth,
            HistorySummaryCard historySummary,
            I983StatusCard i983Status,         // null when not F1_STEM_OPT
            TrainerContextCard trainerContext, // null if no project / no trainer
            List<EvaluationTimelineEntry> timeline
    ) {}

    public record EvalueeProfile(
            UUID lifecycleId,
            UUID internUserId,
            String internName,
            String internEmail,
            String applicantId,
            String employeeId,
            String technology,
            String workAuthType,
            Instant startedAt,
            int monthsInProgram,
            int totalEvaluationsToDate,
            Instant lastEvaluationAt
    ) {}

    /**
     * Status of the evaluation for the calling month — derived in the
     * service from intern_evaluations rows scoped to the current
     * calendar month.
     */
    public record CurrentMonthCard(
            /** NOT_YET_SCHEDULED | SCHEDULED | IN_PROGRESS | PUBLISHED | ACKNOWLEDGED */
            String monthStatus,
            String monthYearLabel,
            UUID currentEvaluationId,        // null when NOT_YET_SCHEDULED
            Instant publishedAt,
            Integer daysSincePublish,        // only when PUBLISHED and not yet acked
            boolean actionNeeded
    ) {}

    public record HistorySummaryCard(
            int totalEvaluations,
            Double averageOverallScore,      // null when no PUBLISHED rows yet
            String trend                      // IMPROVING | STABLE | DECLINING | INSUFFICIENT_DATA
    ) {}

    public record I983StatusCard(
            String planStatus,                // NOT_INITIATED | DRAFT | APPROVED | etc.
            Instant lastI983EvaluationAt,
            String lastI983Status,
            LocalDate nextDueDate,            // placeholder in Phase 1 (null); Phase 3 wires
            Integer daysUntilNext
    ) {}

    public record TrainerContextCard(
            UUID currentProjectId,
            String currentProjectTitle,
            String currentProjectStatus,
            LocalDate currentProjectDueDate,
            String lastFeedbackDecision,
            Instant lastFeedbackAt,
            Instant lastMeetingScheduledFor,
            String lastMeetingStatus,
            Integer daysSinceLastMeeting,
            String trainerName
    ) {}

    public record EvaluationTimelineEntry(
            UUID evaluationId,
            String entryKind,                 // INTERN_EVALUATION | I983_EVALUATION
            String evaluationType,            // MONTHLY | POST_PROJECT | STEM_OPT_12_MONTH | etc.
            String status,
            Instant publishedAt,
            Instant acknowledgedAt,
            Double overallScore,
            String summary                    // first 200 chars of feedback
    ) {}

    // ── Right-side panel ──────────────────────────────────────────────────

    public record RightPanelResponse(
            String monthYearLabel,
            // Home view (aggregates) — populated when lifecycleId omitted
            HomeAggregate homeAggregate,
            // Evaluee view — populated when lifecycleId provided
            EvalueePanelContext evalueeContext
    ) {}

    public record HomeAggregate(
            long activeEvaluees,
            long evaluationsThisMonth,
            long pendingAcknowledgments
    ) {}

    public record EvalueePanelContext(
            UUID lifecycleId,
            String internName,
            String employeeId,
            String technology,
            String workAuthType,
            int monthsInProgram,
            Instant lastEvaluationAt,
            String lastEvaluationStatus,
            boolean isStemOpt
    ) {}
}
