package com.anvicorp.api.erm.reports;

import java.time.LocalDate;
import java.util.List;

/** ERM Phase 7 — DTOs for the 7 reports + the shared filter envelope. */
public final class ErmReportsDtos {

    private ErmReportsDtos() {}

    public record ReportFilters(
            LocalDate from,
            LocalDate to,
            String jobType,
            java.util.UUID jobId,
            java.util.UUID ermOwnerId,
            java.util.UUID trainerId,
            java.util.UUID evaluatorId,
            java.util.UUID managerId,
            String scope               // mine | all
    ) {}

    // 1. Pipeline funnel
    public record FunnelStage(
            String stage,
            long count,
            Double conversionFromPrevious,    // null on first stage
            Double avgDaysFromPrevious        // null on first stage
    ) {}

    public record PipelineFunnelData(
            LocalDate from, LocalDate to,
            List<FunnelStage> stages,
            long uniqueApplicants
    ) {}

    // 2. Time-to-hire
    public record TimeToHireBucket(
            String label,
            long count,
            Double avgDays,
            Double medianDays,
            Double p90Days
    ) {}

    public record TimeToHireData(
            LocalDate from, LocalDate to,
            Double avgDays, Double medianDays, Double p90Days,
            long signedCount,
            List<TimeToHireBucket> byJobType,
            List<TimeToHireBucket> byMonth
    ) {}

    // 3. Application decision funnel
    public record DecisionSlice(
            String decision,
            long count,
            Double pct
    ) {}

    public record ReasonCount(
            String reasonCode,
            String humanLabel,
            long count
    ) {}

    public record DecisionFunnelData(
            LocalDate from, LocalDate to,
            long closedTotal,
            List<DecisionSlice> decisions,
            List<ReasonCount> topReasons
    ) {}

    // 4. Completion rate
    public record CompletionBucket(
            String mentorRole,                 // trainer | evaluator | manager
            java.util.UUID mentorId,
            String mentorName,
            long activated,
            long completed,
            long resigned,
            long terminated,
            long inProgress
    ) {}

    public record CompletionRateData(
            LocalDate from, LocalDate to,
            long totalActivated,
            long totalCompleted,
            long totalResigned,
            long totalTerminated,
            long totalInProgress,
            List<CompletionBucket> byTrainer,
            List<CompletionBucket> byEvaluator,
            List<CompletionBucket> byManager
    ) {}

    // 5. Attrition
    public record AttritionByType(String exitType, long count, Double pct) {}

    public record AttritionData(
            LocalDate from, LocalDate to,
            long totalExited,
            List<AttritionByType> byType,
            List<ReasonCount> topReasons
    ) {}

    // 6. Evaluation distribution
    public record ScoreBucket(int score, long count) {}

    public record EvaluatorBucket(
            java.util.UUID evaluatorId,
            String evaluatorName,
            long evaluations,
            Double avgScore
    ) {}

    public record EvaluationDistributionData(
            LocalDate from, LocalDate to,
            long totalEvaluations,
            Double avgScore,
            List<ScoreBucket> histogram,
            List<EvaluatorBucket> byEvaluator
    ) {}

    // 7. Timesheet compliance
    public record InternTimesheetCompliance(
            java.util.UUID internUserId,
            String internName,
            long weeksTracked,
            long onTimeSubmitted,
            long approvedFirstTry,
            long everRejected,
            Double onTimePct,
            Double firstTryPct
    ) {}

    public record TimesheetComplianceData(
            LocalDate from, LocalDate to,
            long totalWeeks,
            Double aggregateOnTimePct,
            Double aggregateFirstTryPct,
            List<InternTimesheetCompliance> perIntern
    ) {}
}
