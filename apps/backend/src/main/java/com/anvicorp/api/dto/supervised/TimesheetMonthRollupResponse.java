package com.anvicorp.api.dto.supervised;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.anvicorp.api.enums.TimesheetStatus;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Phase B2 — staff-facing weekly rollup for the ERM verify + Manager
 * approve surfaces. ONE shape feeds both pages: rows are interns in
 * scope, columns are the Mon–Fri work-weeks of the selected month.
 *
 * <p>Each cell carries the week total + status badge; daily detail is
 * folded in as {@code TimesheetDayResponse}s so the UI can expand a
 * cell without an extra round-trip.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TimesheetMonthRollupResponse(
        String monthYear,
        /** Column headers — week boundary + which weekdays fall in-month. */
        List<MonthColumn> columns,
        /** Roster-wide counts for the strip above the table. */
        Summary summary,
        List<InternRow> interns
) {
    public record MonthColumn(
            LocalDate weekStart,
            int weekNumber,
            List<DayOfWeek> daysInMonth
    ) {}

    public record InternRow(
            UUID lifecycleId,
            UUID internUserId,
            String fullName,
            String employeeId,
            String technologyTitle,
            /** Owning manager id — for the manager scope check + display. */
            UUID managerId,
            String managerName,
            BigDecimal monthTotalHours,
            List<WeekCell> weeks
    ) {}

    public record WeekCell(
            LocalDate weekStart,
            int weekNumber,
            /** Null when the intern hasn't created the week. */
            UUID timesheetId,
            /** Null → never touched (MISSING). */
            TimesheetStatus status,
            BigDecimal totalHours,
            /** Day rows for in-month weekdays only. Empty when no
             *  timesheet exists or no hours were entered yet. */
            List<TimesheetDayResponse> days,
            /** Submission, verify, approve, reject stamps for the audit
             *  glance the UI shows on expand. */
            java.time.Instant submittedAt,
            String verifiedByName,
            java.time.Instant verifiedAt,
            String approvedByName,
            java.time.Instant approvedAt,
            String reviewNote
    ) {}

    public record Summary(
            int totalInterns,
            int submittedWeeks,
            int verifiedWeeks,
            int approvedWeeks,
            int rejectedWeeks,
            int missingWeeks
    ) {}
}
