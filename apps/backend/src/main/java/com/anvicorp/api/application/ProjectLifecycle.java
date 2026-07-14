package com.anvicorp.api.application;

import com.anvicorp.api.enums.ProjectStatus;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for {@link ProjectStatus} transitions. Same shape as
 * {@link ApplicationLifecycle} / {@link EngagementLifecycle} — a
 * {@code from → allowedTo} map consulted by the workflow service before any
 * write.
 *
 * <pre>
 *   NOT_STARTED     → IN_PROGRESS | SUBMITTED
 *   IN_PROGRESS     → SUBMITTED
 *   SUBMITTED       → IN_PROGRESS                  (intern revises, no review yet)
 *                   | RETURNED                     (legacy single-reviewer return)
 *                   | TECH_APPROVED                (tech supervisor approves)
 *                   | PENDING_VIVA                 (trainer "Review &amp; approve" — routes to evaluator Q&amp;A)
 *                   | COMPLETED                    (legacy single-reviewer complete)
 *   RETURNED        → IN_PROGRESS                  (intern resumes work)
 *                   | SUBMITTED                    (intern resubmits)
 *   TECH_APPROVED   → PENDING_VIVA                 (RM schedules viva)
 *                   | IN_PROGRESS                  (RM returns for revisions)
 *                   | COMPLETED                    (RM accepts without viva)
 *   PENDING_VIVA    → COMPLETED                    (RM signs after viva)
 *                   | IN_PROGRESS                  (RM returns post-viva)
 *   COMPLETED       → terminal
 * </pre>
 *
 * Same-state self-transitions are legal no-ops (handled by the service guard;
 * NOT listed here). Terminal {@code COMPLETED} has an empty allow-set.
 *
 * <h2>Backwards compatibility</h2>
 * The legacy {@code SUBMITTED → COMPLETED} edge is retained so projects on
 * stacks where the two-role workflow doesn't apply (e.g. an OTHER tech-stack
 * project, manual sign-off) keep working without forcing every callsite onto
 * the two-reviewer path.
 */
public final class ProjectLifecycle {

    private ProjectLifecycle() {}

    public static final Map<ProjectStatus, Set<ProjectStatus>> LEGAL_TRANSITIONS = Map.of(
            ProjectStatus.NOT_STARTED, EnumSet.of(
                    ProjectStatus.IN_PROGRESS,
                    ProjectStatus.SUBMITTED),
            ProjectStatus.IN_PROGRESS, EnumSet.of(
                    ProjectStatus.SUBMITTED),
            ProjectStatus.SUBMITTED, EnumSet.of(
                    ProjectStatus.IN_PROGRESS,
                    ProjectStatus.RETURNED,
                    ProjectStatus.TECH_APPROVED,
                    ProjectStatus.PENDING_VIVA,
                    ProjectStatus.COMPLETED),
            ProjectStatus.RETURNED, EnumSet.of(
                    ProjectStatus.IN_PROGRESS,
                    ProjectStatus.SUBMITTED),
            ProjectStatus.TECH_APPROVED, EnumSet.of(
                    ProjectStatus.PENDING_VIVA,
                    ProjectStatus.IN_PROGRESS,
                    ProjectStatus.COMPLETED),
            ProjectStatus.PENDING_VIVA, EnumSet.of(
                    ProjectStatus.COMPLETED,
                    ProjectStatus.IN_PROGRESS),
            ProjectStatus.COMPLETED, EnumSet.noneOf(ProjectStatus.class));

    /** True for the terminal status. Useful for read-side gating. */
    public static boolean isTerminal(ProjectStatus s) {
        return s == ProjectStatus.COMPLETED;
    }

    /** Whether the listed move is allowed (same-state returns true). */
    public static boolean isLegal(ProjectStatus from, ProjectStatus to) {
        if (from == to) return true;
        Set<ProjectStatus> allowed = LEGAL_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }
}
