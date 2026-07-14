package com.anvicorp.api.dto.executive;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Phase-2 weekly-cycle pulse. Counts + rates for this week and last week's
 * overdue tail. All aggregate; no per-intern data.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WeeklyCycleHealthResponse {
    private long activeInternsThisWeek;

    private long reportsSubmittedThisWeek;
    /** reportsSubmittedThisWeek / activeInternsThisWeek. Null when no active interns. */
    private Double reportSubmissionRate;
    /** SUBMITTED reports awaiting any supervisor's approval (program-wide). */
    private long reportsAwaitingApproval;

    private long timesheetsSubmittedThisWeek;
    /** timesheetsSubmittedThisWeek / activeInternsThisWeek. Null when no active interns. */
    private Double timesheetSubmissionRate;
    private long timesheetsAwaitingApproval;

    /** Active interns who didn't submit a report for LAST week. */
    private long overdueReportsLastWeek;
    /** Active interns who didn't submit a timesheet for LAST week. */
    private long overdueTimesheetsLastWeek;
}
