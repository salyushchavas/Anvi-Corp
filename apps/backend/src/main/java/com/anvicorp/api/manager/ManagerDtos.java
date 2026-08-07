package com.anvicorp.api.manager;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Manager Phase 1 — DTO shapes for the Executive Overview and the
 *  Applicant Pipeline list. Read-only; portfolio-wide for MANAGER and
 *  SUPER_ADMIN (per-manager scoping arrives in Phase 3 with the
 *  Active Interns / Timesheet surfaces). */
public final class ManagerDtos {

    private ManagerDtos() {}

    // ── Overview ──────────────────────────────────────────────────────────

    /** Headline payload for /careers/manager (Executive Overview). */
    public record OverviewResponse(
            CallerView caller,
            /** Count per InternLifecycleStatus, all 13 keys present
             *  (zero-filled). Canonical funnel source. */
            Map<String, Long> lifecycleCounts,
            /** Count per ApplicationStatus, only non-zero keys
             *  populated. Mirrors the early funnel. */
            Map<String, Long> applicationCounts,
            /** Coarse buckets the Home cards read directly. */
            HeadlineBuckets buckets,
            /** Conversion-rate KPIs. */
            ConversionKpis kpis,
            /** Interviews with {@code status=COMPLETED} and
             *  {@code manager_hire_decision IN ('PENDING','HOLD')} — the
             *  same set the Hire Approvals queue lists. Drives the
             *  top-of-page "Action Required" KPI so the manager sees the
             *  actionable count on landing instead of scrolling to the
             *  Sections grid. Mirrors
             *  {@code /api/v1/manager/hire-approvals?pageSize=1}
             *  .totalElements — kept as a top-level field so no extra
             *  round-trip is needed. */
            long pendingHireApprovals,
            Instant generatedAt
    ) {}

    public record CallerView(
            UUID userId,
            String fullName,
            String email,
            String role,         // MANAGER | SUPER_ADMIN
            boolean superAdmin
    ) {}

    /** Pre-computed buckets the Home count cards bind to so the UI
     *  doesn't have to sum the raw status maps itself. */
    public record HeadlineBuckets(
            long totalApplications,           // applications.* count
            long applicantsInPipeline,        // APPLIED + SCREENING_* + SHORTLISTED + INTERVIEW_* + SELECTED_CONDITIONAL + OFFERED
            long offersAwaitingSignature,     // offers.status = SENT
            long prospectiveNewHires,         // users.lifecycle_status in OFFER_SIGNED..ONBOARDING_ACCEPTED
            long activeInterns,               // users.lifecycle_status = ACTIVE_INTERN
            long inactiveInterns              // users.lifecycle_status = INACTIVE_INTERN
    ) {}

    /** Conversion-rate KPIs, percentages 0-100, null when denominator is zero. */
    public record ConversionKpis(
            Double shortlistConversionPct,    // (interview-or-later) / (applied-or-later)
            Double interviewCompletionPct,    // INTERVIEWED+ / INTERVIEW_SCHEDULED+
            Double offerSignaturePct,         // OFFER_SIGNED-or-later / OFFER_SENT-or-later
            long offersPendingOver7Days       // pipeline-level overdue: SENT with sent_at < now-7d
    ) {}

    // ── Pipeline ──────────────────────────────────────────────────────────

    public record PipelineRow(
            UUID applicationId,
            String applicantName,
            String applicantId,
            String applicantEmail,
            String jobTitle,
            String jobType,
            String technology,               // null until JobPosting.technology lands
            String stage,                    // ApplicationStatus name
            String latestInterviewStatus,    // null if no interview rows yet
            Instant lastDecisionAt,
            long ageDays,
            UUID ermOwnerId,
            String ermOwnerName,
            LocalDate expectedStartDate      // latest offer.start_date if any
    ) {}

    public record PipelineResponse(
            List<PipelineRow> items,
            int page,
            int pageSize,
            long totalElements,
            int totalPages,
            FilterOptions filters
    ) {}

    /** Distinct values the UI feeds into its dropdowns so the user can't
     *  invent a value the backend wouldn't accept. */
    public record FilterOptions(
            List<String> stages,
            List<String> jobTypes,
            List<ErmOwnerOption> ermOwners
    ) {}

    public record ErmOwnerOption(
            UUID userId,
            String fullName
    ) {}

    // ── Onboarding Health ────────────────────────────────────────────────

    /** Per-new-hire row on the Onboarding Health page. Status + counts +
     *  due dates only — never carries form contents, encrypted fields,
     *  document file contents, or {@code internalNote}-class ERM-only
     *  text. */
    public record OnboardingRow(
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String internEmail,
            String employeeId,
            String workAuthType,
            /** users.lifecycle_status — OFFER_SIGNED | EMPLOYEE_ID_CREATED |
             *  ONBOARDING_ASSIGNED | ONBOARDING_ACCEPTED. */
            String lifecycleStatus,
            LocalDate tentativeStartDate,
            /** Negative when start date is in the future. Positive when
             *  already passed. Null when no start date is set yet. */
            Long daysUntilStart,
            /** Coarse risk flag — true when start date is within 7 days
             *  (or past) AND lifecycle is not yet ONBOARDING_ACCEPTED. */
            boolean startDateAtRisk,
            /** Document packet roll-up. Null when no packet has been
             *  assigned yet (still at OFFER_SIGNED / EMPLOYEE_ID_CREATED). */
            DocumentSummary documents,
            /** Compliance gate roll-up. Values can be null when the
             *  underlying row doesn't exist yet (e.g. I-9 not started). */
            ComplianceSummary compliance,
            UUID ermOwnerId,
            String ermOwnerName,
            UUID managerId,
            String managerName
    ) {}

    /** Counts only — values come from a GROUP-BY-status aggregate, no
     *  per-document content surfaced. */
    public record DocumentSummary(
            String packetStatus,           // DRAFT|ASSIGNED|IN_PROGRESS|ALL_SUBMITTED|COMPLETED|CANCELLED
            int totalTasks,
            int acceptedTasks,
            int submittedTasks,            // SUBMITTED + UNDER_REVIEW
            int pendingTasks,
            int rejectedTasks,             // REJECTED + RESEND_REQUESTED
            int waivedTasks,
            boolean hasRejected,
            Instant lastReviewedAt
    ) {}

    /** Per-compliance-gate status + due-date metadata. Never the
     *  encrypted underlying field values. */
    public record ComplianceSummary(
            String i9Status,               // NOT_STARTED | SECTION_2_PENDING | SECTION_1_COMPLETE | COMPLETED | REOPENED | null
            LocalDate i9Section2DueDate,
            boolean i9Overdue,
            String everifyStatus,          // PENDING_SUBMISSION | OPEN | EMPLOYMENT_AUTHORIZED | TENTATIVE_NONCONFIRMATION | FINAL_NONCONFIRMATION | CLOSED | null
            LocalDate everifyDueBy,
            boolean everifyOverdue,
            LocalDate workAuthValidUntil,
            boolean workAuthExpiringSoon  // within 30 days of today
    ) {}

    public record OnboardingResponse(
            List<OnboardingRow> items,
            int page,
            int pageSize,
            long totalElements,
            int totalPages
    ) {}

    /** Top-of-page summary strip on the Onboarding Health page. */
    public record OnboardingSummary(
            long offersAwaitingSignature,
            long newHiresOnboarding,        // OFFER_SIGNED..ONBOARDING_ASSIGNED
            long onboardingAccepted,        // ONBOARDING_ACCEPTED (waiting on doc-completion trigger to flip them)
            long i9Overdue,
            long everifyOverdue,
            long startDateAtRisk
    ) {}

    public record OnboardingFilterOptions(
            List<String> lifecycleStages,   // the 4 onboarding-window stages
            List<String> workAuthTypes,
            List<ErmOwnerOption> ermOwners
    ) {}

    // ── Active Interns (Phase 3A) ────────────────────────────────────────

    public record ProjectState(
            String status,             // ASSIGNED|IN_PROGRESS|SUBMITTED|RETURNED|COMPLETED|CANCELLED|null
            String projectTitle,
            LocalDate dueDate,
            boolean atRisk             // no active project OR past due
    ) {}

    public record MeetingState(
            String lastMeetingStatus,  // SCHEDULED|COMPLETED|NO_SHOW|CANCELLED|null
            Instant lastMeetingAt,
            Long daysSinceLastMeeting, // null when none
            boolean atRisk             // > 7 days since last SCHEDULED/COMPLETED
    ) {}

    public record EvaluationState(
            String lastEvaluationStatus, // PUBLISHED|ACKNOWLEDGED|AMENDED|null
            Instant lastPublishedAt,
            Integer overallScore,        // manager-visible
            String recommendation,       // manager-visible enum, never the internal_notes free text
            Long daysSinceLastPublished,
            boolean atRisk               // > 35 days OR no published eval ever
    ) {}

    public record TimesheetState(
            String currentWeekStatus,    // DRAFT|SUBMITTED|APPROVED|REJECTED|null
            String previousWeekStatus,
            int recentRejections,        // count in last 4 weeks
            boolean atRisk               // previous-week missing OR ≥2 recent rejections
    ) {}

    public record ActiveInternRow(
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String internEmail,
            String employeeId,
            String technology,
            String workAuthType,
            String health,               // ACTIVE_ON_TRACK | ACTIVE_AT_RISK
            ProjectState project,
            MeetingState meeting,
            EvaluationState evaluation,
            TimesheetState timesheet,
            UUID managerId,
            String managerName,
            UUID ermOwnerId,
            String ermOwnerName,
            UUID trainerId,
            String trainerName,
            UUID evaluatorId,
            String evaluatorName,
            Instant startedAt,
            Integer monthsInProgram
    ) {}

    public record ActiveInternResponse(
            List<ActiveInternRow> items,
            int page,
            int pageSize,
            long totalElements,
            int totalPages
    ) {}

    public record ActiveInternSummary(
            long activeInternsTotal,
            long onTrack,
            long atRisk,
            long noProjectAssigned,
            long trainerMeetingMissing,   // last meeting > 7 days OR none
            long evaluationOverdue,       // last published > 35 days OR none
            long timesheetMissingThisWeek
    ) {}

    public record UserOption(
            UUID userId,
            String fullName
    ) {}

    public record ActiveInternFilterOptions(
            List<String> technologies,
            List<UserOption> trainers,
            List<UserOption> evaluators,
            List<UserOption> managers,
            List<ErmOwnerOption> ermOwners
    ) {}

    // ── Timesheet Approvals (Phase 3B) ───────────────────────────────────

    /** One timesheet row on the Manager Approvals queue. Hours / description /
     *  daily breakdown are populated ONLY when {@code canAct == true}
     *  (caller is the intern's assigned manager OR SUPER_ADMIN). For
     *  non-owned rows the manager sees status + approver + week only. */
    public record TimesheetRow(
            UUID timesheetId,
            UUID internUserId,
            String internName,
            String employeeId,
            String technology,
            UUID managerId,
            String managerName,
            UUID ermOwnerId,
            String ermOwnerName,
            LocalDate weekStart,
            String status,                  // DRAFT|SUBMITTED|APPROVED|REJECTED
            UUID approvedById,
            String approvedByName,
            Instant approvedAt,
            /** Server-authoritative — true iff caller can approve/reject. */
            boolean canAct,
            /** Hours masking: null when canAct is false (and caller is not
             *  SUPER_ADMIN). Total weekly hours. */
            java.math.BigDecimal hours,
            /** Description (intern narrative). Null when canAct is false. */
            String description,
            /** Per-day breakdown. Null when canAct is false. */
            List<TimesheetDayBreakdown> days,
            /** Reject reason from a previous rejection cycle — null on the
             *  fresh row. Visible regardless of canAct (no PII). */
            String reviewNote
    ) {}

    public record TimesheetDayBreakdown(
            String dayOfWeek,               // MONDAY|TUESDAY|...
            java.math.BigDecimal hours,
            String notes
    ) {}

    public record TimesheetListResponse(
            List<TimesheetRow> items,
            int page,
            int pageSize,
            long totalElements,
            int totalPages
    ) {}

    public record TimesheetFilterOptions(
            List<String> statuses,          // DRAFT|SUBMITTED|APPROVED|REJECTED
            List<String> technologies,
            List<UserOption> managers,
            List<ErmOwnerOption> ermOwners
    ) {}

    // ── Risk Center (Phase 4A) ───────────────────────────────────────────

    /** One open or recently-resolved exception on the Risk Center queue.
     *  Mirrors {@code ErmEscalationDtos.ExceptionRow} with the addition
     *  of the intern's reporting-manager so the UI can render the owner
     *  column and the "My interns" filter without a second round-trip. */
    public record RiskRow(
            UUID id,
            String exceptionType,
            String severity,                // URGENT | WARN | INFO
            String status,                  // OPEN | ASSIGNED | IN_PROGRESS | RESOLVED | DISMISSED | AUTO_RESOLVED
            UUID subjectUserId,
            String subjectName,
            String subjectEmployeeId,
            UUID internLifecycleId,
            UUID assignedToId,
            String assignedToName,
            UUID managerId,
            String managerName,
            UUID ermOwnerId,
            String ermOwnerName,
            Instant openedAt,
            Instant lastSeenAt,
            Instant assignedAt,
            Instant resolvedAt,
            int ageDays,
            String subjectResourceType,
            UUID subjectResourceId,
            String payloadJson              // sanitised — already metadata-only
    ) {}

    public record RiskListResponse(
            List<RiskRow> items,
            int page,
            int pageSize,
            long totalElements,
            int totalPages
    ) {}

    /** Top-of-page summary strip. "Resolved this period" = resolvedAt
     *  within the trailing 30 days so the figure stays steady through
     *  the month. */
    public record RiskSummary(
            long totalOpen,                 // OPEN + ASSIGNED + IN_PROGRESS
            long urgent,
            long warn,
            long info,
            long assigned,                  // status = ASSIGNED + IN_PROGRESS
            long resolvedLast30Days,
            List<RiskTypeCount> byType      // top-5 by open count, severity-weighted
    ) {}

    public record RiskTypeCount(
            String exceptionType,
            String severity,
            long openCount
    ) {}

    public record RiskFilterOptions(
            List<String> severities,        // URGENT | WARN | INFO
            List<String> statuses,
            List<String> exceptionTypes,
            List<UserOption> assignees,
            List<UserOption> managers
    ) {}

    /** Body for POST /risk-center/{id}/assign. */
    public record AssignRiskRequest(
            @jakarta.validation.constraints.NotNull UUID assigneeUserId
    ) {}

    /** Body for POST /risk-center/{id}/note — note must be ≥5 chars
     *  (mirrors the existing ERM constraint). Wave 3 — bounds enforced
     *  at the DTO layer so the service can trust the input shape. */
    public record NoteRiskRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(min = 5, max = 2000)
            String note
    ) {}

    /** Body for POST /risk-center/{id}/resolve — reasonCode required;
     *  resolutionNote required (≥10 chars per existing ERM constraint). */
    public record ResolveRiskRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 40)
            String reasonCode,
            @jakarta.validation.constraints.Size(max = 500)
            String reasonText,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(min = 10, max = 2000)
            String resolutionNote
    ) {}
}
