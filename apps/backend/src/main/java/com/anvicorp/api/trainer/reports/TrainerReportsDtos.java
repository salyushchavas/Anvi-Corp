package com.anvicorp.api.trainer.reports;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Trainer Phase 4 — DTOs for the Monthly Progress Report. */
public final class TrainerReportsDtos {

    private TrainerReportsDtos() {}

    /** Headline KPIs for the report period. */
    public record HeadlineStats(
            String monthYear,
            int activeInterns,
            int projectsAssigned,
            int projectsCompleted,
            int projectsInRevision,
            int projectsEscalated,
            int weeklyMeetingsScheduled,
            int weeklyMeetingsCompleted,
            int weeklyMeetingsMissed,
            int weeklyMeetingsCancelled,
            int pendingReviewBacklog,
            Double averageReviewTurnaroundHours
    ) {}

    /** One bucket on the project-status chart. */
    public record StatusBucket(
            String label,
            int count
    ) {}

    /** One bucket on the meeting-status chart. */
    public record MeetingBucket(
            String label,
            int count
    ) {}

    /** Per-intern roll-up row — also the CSV export row shape. */
    public record InternRollup(
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String employeeId,
            String technologyArea,
            int projectsAssignedCount,
            int projectsCompletedCount,
            int projectsInRevisionCount,
            int projectsEscalatedCount,
            int weeklyMeetingsScheduled,
            int weeklyMeetingsCompleted,
            int weeklyMeetingsMissed,
            int pendingReviewCount,
            Double averageReviewTurnaroundHours,
            Instant latestReviewDate
    ) {}

    public record MonthlyProgressReport(
            HeadlineStats headline,
            List<StatusBucket> projectStatusDistribution,
            List<MeetingBucket> weeklyMeetingAttendance,
            List<InternRollup> internRollups
    ) {}

    public record FilterOptions(
            List<String> monthYears,
            List<InternOption> interns
    ) {}

    public record InternOption(
            UUID internLifecycleId,
            String internName,
            String employeeId
    ) {}
}
