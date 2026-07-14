package com.anvicorp.api.enums;

/**
 * Project lifecycle. Distinct from {@link WorkAssignmentStatus} because
 * Projects are long-running deliverables with multiple submissions, a
 * checklist, and a split review outcome — Returned (intern fixes) vs
 * Completed (terminal lock).
 *
 * <pre>
 *   NOT_STARTED     Created by supervisor; intern hasn't started.
 *   IN_PROGRESS     Intern moved off the starting line; can update progress.
 *   SUBMITTED       Intern sent the deliverables; tech supervisor reviews next.
 *   RETURNED        Supervisor sent back with feedback; intern updates + resubmits.
 *   TECH_APPROVED   Tech supervisor signed off; awaiting Reporting Manager viva.
 *   PENDING_VIVA    Reporting Manager scheduled the viva.
 *   COMPLETED       Terminal. Locked — no edits, no resubmits.
 * </pre>
 *
 * <h2>Two-role workflow</h2>
 * {@code TECH_APPROVED} + {@code PENDING_VIVA} + the
 * {@link com.anvicorp.api.enums.UserRole#REPORTING_MANAGER} role together
 * implement the two-reviewer sign-off: the technical supervisor judges the
 * deliverable, the reporting manager runs a viva and signs final completion.
 * Either reviewer can return for revisions, dropping the project back to
 * {@code IN_PROGRESS}.
 *
 * <h2>Backwards compatibility</h2>
 * The legacy single-reviewer happy path ({@code SUBMITTED → COMPLETED}) is
 * preserved by {@link com.anvicorp.api.application.ProjectLifecycle} so
 * projects on stacks without the two-role workflow keep working unchanged.
 */
public enum ProjectStatus {
    NOT_STARTED,
    IN_PROGRESS,
    SUBMITTED,
    RETURNED,
    TECH_APPROVED,
    PENDING_VIVA,
    COMPLETED
}
