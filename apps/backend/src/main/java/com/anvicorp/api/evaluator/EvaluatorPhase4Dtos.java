package com.anvicorp.api.evaluator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Evaluator Phase 4 — DTOs for Evaluation History, Monthly Reports,
 *  and Settings surfaces. */
public final class EvaluatorPhase4Dtos {

    private EvaluatorPhase4Dtos() {}

    // ── History ───────────────────────────────────────────────────────────

    /** Row on /careers/evaluator/evaluation-history. Combines monthly +
     *  I-983 + final into a single timeline shape. */
    public record HistoryRow(
            UUID evaluationId,
            String entryKind, // MONTHLY | I983 | FINAL
            UUID internLifecycleId,
            String internName,
            String employeeId,
            String evaluationType,
            String status,
            int version,
            LocalDate periodStart,
            LocalDate periodEnd,
            Instant scheduledFor,
            Instant publishedAt,
            Instant acknowledgedAt,
            Integer overallScore,
            String recommendation
    ) {}

    public record HistoryPage(
            List<HistoryRow> items,
            int page,
            int pageSize,
            long totalElements,
            int totalPages
    ) {}

    // ── Reports ──────────────────────────────────────────────────────────

    public record ReportKpis(
            long totalEvaluations,
            long publishedCount,
            long acknowledgedCount,
            long pendingAckCount,
            long amendedCount,
            Double averageOverallScore,
            Double averageDaysToAck
    ) {}

    public record RecommendationBucket(
            String recommendation, // EXCELLENT | GOOD | ... | REHIRE_ELIGIBLE
            long count,
            double pct
    ) {}

    public record CriterionAverages(
            Double technical,
            Double communication,
            Double professionalism,
            Double learningApplication
    ) {}

    public record InternRollup(
            UUID internLifecycleId,
            String internName,
            String employeeId,
            long evaluationsThisPeriod,
            Double averageOverallScore,
            Instant lastPublishedAt
    ) {}

    public record MonthlyReport(
            int year,
            int month,
            String monthLabel,
            ReportKpis kpis,
            List<RecommendationBucket> recommendationMix,
            CriterionAverages criterionAverages,
            List<InternRollup> perInternRollup
    ) {}

    // ── Settings ─────────────────────────────────────────────────────────

    public record EvaluatorSettings(
            // Preferences tab
            Short defaultDurationMinutes,
            String reminderFrequency, // DAILY | WEEKLY | NEVER
            // Notifications tab
            Boolean notifyAcknowledged,
            Boolean notifyDsoWindow,
            Boolean prefsRemindersEmail,
            Boolean prefsEngagementUpdatesEmail,
            // About tab (read-only)
            String fullName,
            String email,
            String zoomEmail
    ) {}

    public record SettingsUpdateRequest(
            Short defaultDurationMinutes,
            String reminderFrequency,
            Boolean notifyAcknowledged,
            Boolean notifyDsoWindow,
            Boolean prefsRemindersEmail,
            Boolean prefsEngagementUpdatesEmail
    ) {}
}
