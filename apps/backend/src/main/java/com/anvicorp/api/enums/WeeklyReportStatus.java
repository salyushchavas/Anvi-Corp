package com.anvicorp.api.enums;

/**
 * Weekly report lifecycle. Mirrors the timesheet-review flow but on the
 * narrative side of the weekly cycle.
 *
 * <pre>
 *   DRAFT      Intern is composing; supervisor cannot see it yet.
 *   SUBMITTED  Intern sent for review; supervisor can return or approve.
 *   RETURNED   Supervisor sent back with notes — intern edits and re-submits.
 *   APPROVED   Terminal. Locked — edits are blocked, return/approve no-ops.
 * </pre>
 */
public enum WeeklyReportStatus {
    DRAFT,
    SUBMITTED,
    RETURNED,
    APPROVED
}
