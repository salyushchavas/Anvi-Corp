package com.anvicorp.api.erm.exit;

import java.util.List;

/**
 * ERM Phase 7 — canonical list of the 8 checklist items every
 * ExitRecord gets when it's initiated. Keeping the keys here (rather
 * than scattered string literals) makes auto-flip + UI rendering
 * consistent.
 *
 * <p>Auto-flip behaviour (handled by listeners / detectors):</p>
 * <ul>
 *   <li>{@link #GITHUB_REVOKED} — set to COMPLETE by
 *       {@code GithubRevocationListener} when revocation succeeds.</li>
 *   <li>{@link #EXIT_FEEDBACK_SUBMITTED} — set to COMPLETE when intern
 *       posts feedback via the existing intern Phase 8 endpoint.</li>
 *   <li>{@link #OUTSTANDING_TIMESHEETS} / {@link #OUTSTANDING_PROJECTS}
 *       — auto-COMPLETE on read in {@code ErmExitService.getDetail}
 *       when no in-flight rows remain (no scheduler needed).</li>
 *   <li>{@link #FINAL_EVALUATION} — set to COMPLETE when ERM links a
 *       PUBLISHED FINAL evaluation via
 *       {@code ErmExitService.linkFinalEvaluation}.</li>
 *   <li>{@link #ASSETS_RETURNED}, {@link #DOCUMENTS_ARCHIVED},
 *       {@link #FINAL_PAYROLL_CONFIRMED} — manual ERM toggles only.</li>
 * </ul>
 */
public final class ExitChecklistItemRegistry {

    private ExitChecklistItemRegistry() {}

    public static final String FINAL_EVALUATION         = "FINAL_EVALUATION";
    public static final String OUTSTANDING_TIMESHEETS   = "OUTSTANDING_TIMESHEETS";
    public static final String OUTSTANDING_PROJECTS     = "OUTSTANDING_PROJECTS";
    public static final String GITHUB_REVOKED           = "GITHUB_REVOKED";
    public static final String ASSETS_RETURNED          = "ASSETS_RETURNED";
    public static final String DOCUMENTS_ARCHIVED       = "DOCUMENTS_ARCHIVED";
    public static final String EXIT_FEEDBACK_SUBMITTED  = "EXIT_FEEDBACK_SUBMITTED";
    public static final String FINAL_PAYROLL_CONFIRMED  = "FINAL_PAYROLL_CONFIRMED";

    public static final List<String> ALL_ITEMS = List.of(
            FINAL_EVALUATION,
            OUTSTANDING_TIMESHEETS,
            OUTSTANDING_PROJECTS,
            GITHUB_REVOKED,
            ASSETS_RETURNED,
            DOCUMENTS_ARCHIVED,
            EXIT_FEEDBACK_SUBMITTED,
            FINAL_PAYROLL_CONFIRMED);

    public static final String STATUS_PENDING        = "PENDING";
    public static final String STATUS_COMPLETE       = "COMPLETE";
    public static final String STATUS_NOT_APPLICABLE = "NOT_APPLICABLE";
    public static final String STATUS_WAIVED         = "WAIVED";

    /** Items the ERM can manually toggle directly. */
    public static final List<String> MANUAL_TOGGLE_ITEMS = List.of(
            ASSETS_RETURNED, DOCUMENTS_ARCHIVED, FINAL_PAYROLL_CONFIRMED);

    /** Items computed/auto-flipped on read (no manual toggle needed). */
    public static final List<String> READ_TIME_COMPUTED_ITEMS = List.of(
            OUTSTANDING_TIMESHEETS, OUTSTANDING_PROJECTS);
}
