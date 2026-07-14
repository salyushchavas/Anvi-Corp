package com.anvicorp.api.trainer.active;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Trainer Phase 1 — DTOs for the Active Interns list + detail. Exactly
 * the 9 columns from Trainer doc §5 (Employee ID, Full name,
 * Phone/email, Technology title, Start date, Current month projects,
 * Weekly meeting status, Evaluator status, Timesheet status) plus the
 * detail expansion. Field RBAC strips all PII per doc §3.
 */
public final class ActiveInternsDtos {

    private ActiveInternsDtos() {}

    // ── List row (the 9 doc §5 columns) ──────────────────────────────────

    public record ActiveInternRow(
            UUID internLifecycleId,
            String employeeId,
            String fullName,
            String email,
            String phone,
            /** Trainer doc §5 "Technology title" — sourced from
             *  Candidate.skillset (or expected_track) at display time. */
            String technologyTitle,
            LocalDate startDate,
            Integer daysActive,
            CurrentMonthProjectsBlock currentMonthProjects,
            MeetingStateBlock weeklyMeeting,
            EvaluationStateBlock evaluation,
            TimesheetStateBlock timesheet,
            ReportingStructure reportingStructure
    ) {}

    public record ActiveInternListPage(
            List<ActiveInternRow> items,
            int page,
            int pageSize,
            long totalElements,
            int totalPages,
            /** Always present — the period the roster was rendered for. */
            String monthYear,
            /** Roster-wide aggregate for the month-summary strip. */
            MonthRosterSummary summary
    ) {}

    /**
     * Roster-wide counts for the month-summary strip above the table.
     * All fields are scoped to the requested month + the row set the
     * roster returned (pre-filter — gives the operator a sense of the
     * whole month, not just what the filters surfaced).
     */
    public record MonthRosterSummary(
            int totalActive,
            int projectsUnassigned,
            int ktNotDone,
            int timesheetsIncomplete,
            int evaluationsOverdue,
            int attentionNeeded,
            /** Phase C — interns with no manager assigned. Surfaced on
             *  the ERM roster so the operator can spot + fix them via
             *  the existing AssignManager flow. Always zero on the
             *  Manager roster (the scope filter implies a manager). */
            int noManager
    ) {}

    // ── Card-by-card state blocks ────────────────────────────────────────

    /** Per-slot project state for the current month_year. Doc §5 column
     *  6 visual: two slots, color-coded by state. */
    public record ProjectSlot(
            UUID id,
            String title,
            String status,
            LocalDate dueDate,
            String state,             // NOT_ASSIGNED | ASSIGNED | IN_PROGRESS | COMPLETED | OVERDUE
            /** Phase 0 KT — NOT_DONE | DONE. Null when no project. */
            String ktStatus,
            Instant ktCompletedAt
    ) {}

    public record CurrentMonthProjectsBlock(
            String monthYear,
            ProjectSlot project1,
            ProjectSlot project2,
            /** NO_PROJECTS | PARTIAL | BOTH_ASSIGNED | OVERDUE | COMPLETE */
            String overallState
    ) {}

    /** Doc §5 column 7 — WeeklyMeeting status. Trainer doc uses 4 doc
     *  labels (SCHEDULED / COMPLETED / MISSED / RESCHEDULED); the
     *  DB stores SCHEDULED / COMPLETED / CANCELLED / NO_SHOW, so the
     *  service maps NO_SHOW → MISSED and CANCELLED → RESCHEDULED. */
    public record MeetingStateBlock(
            Instant lastMeetingAt,
            String lastMeetingStatus,
            Instant nextMeetingAt,
            String state             // SCHEDULED | COMPLETED | MISSED | RESCHEDULED | NONE
    ) {}

    public record EvaluationStateBlock(
            Instant lastPublishedAt,
            String lastEvaluationType,
            Instant nextScheduledAt,
            String state             // SCHEDULED | COMPLETED | OVERDUE | NONE
    ) {}

    public record TimesheetStateBlock(
            LocalDate currentWeekStart,
            String currentWeekStatus,
            Instant lastApprovedAt,
            String state,            // SUBMITTED | APPROVED | REJECTED | MISSING (overall pill)
            /** Phase B2 — per-status counts for the month, drive the
             *  per-stage chip row in the roster's Timesheets cell. All
             *  zero when no rows exist for the month. */
            int submittedCount,
            int verifiedCount,
            int approvedCount,
            int rejectedCount,
            int missingCount,
            int expectedWeeks
    ) {}

    public record ReportingStructure(
            String trainerName,
            String evaluatorName,
            String managerName,
            String ermName,
            /** Phase C — IDs alongside the names so the ERM roster can
             *  detect "no manager" and link the AssignManager flow. */
            UUID managerId,
            UUID trainerId,
            UUID evaluatorId,
            UUID ermId
    ) {}

    // ── Detail view ──────────────────────────────────────────────────────

    public record InternProfile(
            UUID userId,
            String fullName,
            String email,
            String phone,
            String employeeId,
            String technologyTitle,
            LocalDate startDate
    ) {}

    public record SignedOfferSummary(
            String roleTitle,
            /** "Per ANVI policy" sentinel — Trainer doc §3 hides
             *  numeric compensation from Trainer DTOs. */
            String compensationSummary,
            LocalDate tentativeStartDate,
            Instant signedAt
    ) {}

    public record RecentProjectRow(
            UUID id,
            String title,
            String status,
            Short projectNumber,
            String monthYear,
            LocalDate dueDate,
            Instant reviewedAt,
            /** {@code NOT_DONE} or {@code DONE}. Drives the trainer's
             *  Mark KT button + the upcoming monthly-roster KT column. */
            String ktStatus,
            Instant ktCompletedAt,
            String ktMeetingLink,
            /** Live KT Zoom session — populated when the trainer
             *  scheduled one via {@code POST /api/v1/projects/{id}
             *  /kt-schedule}. Null when no session is on the calendar.
             *  zoomStartUrl is the host-only one-click link;
             *  intern-side DTOs MUST strip it before serialising. */
            String ktZoomMeetingId,
            String ktZoomJoinUrl,
            String ktZoomStartUrl,
            Instant ktScheduledFor,
            Integer ktDurationMinutes,
            String ktTimezone
    ) {}

    public record RecentMeetingRow(
            UUID id,
            Instant scheduledFor,
            String status,
            String topic,
            String notesExcerpt
    ) {}

    public record RecentSubmissionRow(
            UUID id,
            UUID projectId,
            String projectTitle,
            Instant submittedAt,
            Short technicalScore,
            Short communicationScore,
            String nextAction
    ) {}

    public record RecentTimesheetRow(
            UUID id,
            LocalDate weekStart,
            String status,
            String hours
    ) {}

    public record ActivityEntry(
            Instant at,
            String entityType,
            UUID entityId,
            String action,
            UUID actorUserId,
            String actorName
    ) {}

    public record ActiveInternDetail(
            UUID internLifecycleId,
            InternProfile intern,
            ActiveInternRow summary,
            SignedOfferSummary signedOffer,
            List<RecentProjectRow> recentProjects,
            List<RecentMeetingRow> recentMeetings,
            List<RecentSubmissionRow> recentSubmissions,
            List<RecentTimesheetRow> recentTimesheets,
            List<ActivityEntry> recentActivity,
            Boolean i983Required,
            String i983StatusBadge
    ) {}
}
