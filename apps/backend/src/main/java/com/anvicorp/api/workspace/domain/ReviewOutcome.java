package com.anvicorp.api.workspace.domain;

/**
 * Reviewer's verdict on a {@link WorkspaceSubmission}.
 *
 * <ul>
 *   <li>{@code PENDING} — submission awaiting evaluator action. Initial state.</li>
 *   <li>{@code APPROVED} — evaluator signed off; project moved to
 *       {@code TECH_APPROVED}. Submission is now read-only.</li>
 *   <li>{@code RETURNED} — evaluator asked for changes; project moved back to
 *       {@code IN_PROGRESS}. Workspace files become editable again. A
 *       subsequent intern submit creates a NEW submission row.</li>
 * </ul>
 */
public enum ReviewOutcome {
    PENDING,
    APPROVED,
    RETURNED
}
