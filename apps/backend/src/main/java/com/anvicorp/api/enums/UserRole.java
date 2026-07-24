package com.anvicorp.api.enums;

/**
 * Role taxonomy for the Anvi Careers platform.
 *
 * <p>Eight roles drive every dashboard. Per-role functionality is layered in
 * dedicated prompts; this enum is the canonical source of truth for both the
 * database string and the Spring Security authority suffix
 * ({@code ROLE_<name>}).</p>
 *
 * <ul>
 *   <li>{@link #INTERN} — applies for jobs, gets hired, runs projects,
 *       submits timesheets. One role covers pre- and post-hire candidate
 *       lifecycle.</li>
 *   <li>{@link #TRAINER} — assigns projects, conducts sessions, marks
 *       project complete (first sign-off).</li>
 *   <li>{@link #EVALUATOR} — runs monthly + final evaluations and the
 *       separate I-983 evaluations for F-1 STEM OPT interns. Auto-linked
 *       at offer-sign from {@code DEFAULT_EVALUATOR_EMAIL} (Phase 8.6.4).
 *       Single Evaluator org-wide per the locked Phase 0 design.</li>
 *   <li>{@link #REPORTING_MANAGER} — post-trainer review session, approves
 *       timesheets (second sign-off).</li>
 *   <li>{@link #MANAGER} — monitors ERM's work and the application pipeline.</li>
 *   <li>{@link #ERM} — shortlisting, onboarding, all pre-hire operations.</li>
 *   <li>{@link #JOBS_ADMIN} — dedicated job-posting owner. Manages the
 *       postings surface (create / edit / status). Reachable only by
 *       typing {@code /careers/adminjobs} — no sidebar link — so the
 *       role is invisible to browsers who don't already know the URL.
 *       Shares posting-management RBAC with {@link #ERM} on the backend
 *       so ERM keeps its existing access.</li>
 *   <li>{@link #SUPER_ADMIN} — owner / god-mode oversight.</li>
 * </ul>
 */
public enum UserRole {
    INTERN,
    TRAINER,
    EVALUATOR,
    REPORTING_MANAGER,
    MANAGER,
    ERM,
    JOBS_ADMIN,
    SUPER_ADMIN
}
