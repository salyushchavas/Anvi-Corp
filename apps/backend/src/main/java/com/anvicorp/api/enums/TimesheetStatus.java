package com.anvicorp.api.enums;

/**
 * Lifecycle for an intern's weekly timesheet. Two-stage approval chain
 * (Phase B): {@code DRAFT → SUBMITTED → VERIFIED → APPROVED}, plus
 * {@code REJECTED} as a side-channel back to DRAFT-editable.
 *
 * <p>{@code VERIFIED} is the new ERM-verify stage; B1 establishes the
 * column so B2 can transition into it. Existing readers that treat
 * "anything but APPROVED" as in-progress continue to work — VERIFIED
 * falls in that bucket until the Manager approves.</p>
 */
public enum TimesheetStatus {
    DRAFT,
    SUBMITTED,
    VERIFIED,
    APPROVED,
    REJECTED
}
