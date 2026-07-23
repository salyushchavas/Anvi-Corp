package com.anvicorp.api.enums;

/**
 * Weekly report lifecycle. Two-stage review, mirrors the timesheet flow:
 *
 * <pre>
 *   DRAFT      Intern is composing; no reviewer sees it yet.
 *   SUBMITTED  Intern sent for review; ERM can verify or return.
 *   VERIFIED   ERM signed off; Evaluator can approve or return.
 *   RETURNED   Sent back with notes from either review stage; intern
 *              edits and re-submits (goes back to SUBMITTED).
 *   APPROVED   Terminal. Locked — edits are blocked, downstream actions no-op.
 * </pre>
 */
public enum WeeklyReportStatus {
    DRAFT,
    SUBMITTED,
    VERIFIED,
    RETURNED,
    APPROVED
}
