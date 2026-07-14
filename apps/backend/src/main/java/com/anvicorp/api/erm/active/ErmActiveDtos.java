package com.anvicorp.api.erm.active;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** ERM Phase 6 — DTO surface for the Active Intern Monitor. */
public final class ErmActiveDtos {

    private ErmActiveDtos() {}

    /** OK | WARN | URGENT. Drives the dot color + sort priority. */
    public enum CardState { OK, WARN, URGENT }

    public record MonitorState(
            CardState state,
            String label,
            String detail
    ) {}

    public record ActiveInternRow(
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String employeeId,
            UUID trainerId,
            String trainerName,
            UUID evaluatorId,
            String evaluatorName,
            UUID managerId,
            String managerName,
            UUID ermId,
            String ermName,
            Instant startedAt,
            int daysActive,
            MonitorState project,
            MonitorState trainerMeeting,
            MonitorState evaluation,
            MonitorState timesheet,
            MonitorState compliance,
            MonitorState escalations,
            long openExceptionCount,
            long urgentExceptionCount
    ) {}

    public record ActiveInternListPage(
            List<ActiveInternRow> items,
            int page,
            int pageSize,
            long totalElements,
            int totalPages
    ) {}

    // ── Detail view sub-records ──────────────────────────────────────────

    public record ProjectSummary(
            UUID id,
            String title,
            String status,
            LocalDate assignmentDate,
            LocalDate dueDate,
            Instant submittedAt,
            UUID assignedById
    ) {}

    public record MeetingSummary(
            UUID id,
            String topic,
            String status,
            Instant scheduledFor,
            UUID hostUserId
    ) {}

    public record EvaluationSummary(
            UUID id,
            String evaluationType,
            String status,
            Instant publishedAt,
            Instant scheduledFor,
            UUID evaluatorId
    ) {}

    public record TimesheetSummary(
            UUID id,
            LocalDate weekStart,
            String status,
            String hours,            // BigDecimal serialised
            Instant approvedAt
    ) {}

    public record ComplianceAlertSummary(
            String label,
            LocalDate date,
            String severity
    ) {}

    public record EscalationSummary(
            UUID id,
            String exceptionType,
            String severity,
            String status,
            Instant openedAt,
            int ageDays
    ) {}

    public record ProjectCard(
            MonitorState state,
            List<ProjectSummary> projects,
            boolean assignNewCta
    ) {}

    public record TrainerMeetingCard(
            MonitorState state,
            List<MeetingSummary> upcoming,
            List<MeetingSummary> past,
            boolean scheduleNewCta
    ) {}

    public record EvaluationCard(
            MonitorState state,
            List<EvaluationSummary> evaluations,
            boolean scheduleNewCta
    ) {}

    public record TimesheetCard(
            MonitorState state,
            String currentWeekStatus,
            LocalDate currentWeekStart,
            List<TimesheetSummary> lastFourWeeks,
            String totalApprovedHours
    ) {}

    public record ComplianceCard(
            MonitorState state,
            String workAuthType,
            LocalDate workAuthExpiresOn,
            String i9Status,
            String everifyStatus,
            String i983Status,
            List<ComplianceAlertSummary> alerts
    ) {}

    public record EscalationsCard(
            MonitorState state,
            List<EscalationSummary> openExceptions,
            List<EscalationSummary> pastExceptions
    ) {}

    public record InternProfile(
            UUID userId,
            String fullName,
            String email,
            String employeeId,
            String workAuthType,
            String signedRoleTitle,
            String compensationSummary,
            Instant signedAt,
            Instant startedAt,
            Instant hiredAt
    ) {}

    public record ActivityEntry(
            Instant at,
            String entityType,
            UUID entityId,
            String action,
            UUID actorUserId,
            String actorName
    ) {}

    public record InternMonitorView(
            UUID internLifecycleId,
            InternProfile intern,
            ActiveInternRow summary,
            ProjectCard project,
            TrainerMeetingCard trainerMeeting,
            EvaluationCard evaluation,
            TimesheetCard timesheet,
            ComplianceCard compliance,
            EscalationsCard escalations,
            List<ActivityEntry> recentActivity
    ) {}
}
