package com.anvicorp.api.application;

import com.anvicorp.api.enums.ApplicationStatus;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for the application status lifecycle.
 *
 * Three things live here:
 *  - {@link #stageIndexOf(ApplicationStatus)} — the 5-stage stepper mapping
 *    (0 Applied → 1 Shortlisted → 2 Interview → 3 Offer → 4 Hired), with -1
 *    for exit statuses.
 *  - {@link #isExited(ApplicationStatus)} — whether the application has left
 *    the funnel (REJECTED / WITHDRAWN / LAPSED / NO_SHOW).
 *  - {@link #LEGAL_TRANSITIONS} — the gated state machine. Defined now,
 *    enforced in phase 1.1b. Existing write-sites are unaware of this table
 *    until then; same-state self-transitions are considered legal no-ops.
 *
 * NO behavior change in this commit: the two duplicated stageIndex helpers
 * in {@code CandidateDashboardService} + {@code CandidateApplicationsService}
 * — and their local EXIT_STATUSES sets — now delegate here.
 */
public final class ApplicationLifecycle {

    private ApplicationLifecycle() {}

    /** Statuses outside the funnel; surfaced as {@code isExited=true} on the wire. */
    private static final Set<ApplicationStatus> EXIT_STATUSES = EnumSet.of(
            ApplicationStatus.REJECTED,
            ApplicationStatus.WITHDRAWN,
            ApplicationStatus.LAPSED,
            ApplicationStatus.NO_SHOW);

    /**
     * 5-stage stepper index. Returns 0 for {@code null} (same fallback the
     * original helpers used so the stepper has a sane default for partial
     * data). Returns -1 for exit statuses — the frontend renders those with
     * the {@code isExited} treatment, not a stage.
     */
    public static int stageIndexOf(ApplicationStatus s) {
        if (s == null) return 0;
        return switch (s) {
            // ERM Phase 2 — HOLD and INFO_REQUESTED stay in the Applied band:
            // the applicant hasn't yet been shortlisted, just paused or pinged
            // for more info.
            case APPLIED, HOLD, INFO_REQUESTED -> 0;
            // Screening is a sub-stage of Shortlisted in the 5-stage stepper —
            // the candidate has been picked out of "Applied" but not yet
            // formally shortlisted, so band=1 keeps the visual progression honest.
            case SCREENING_SENT, SCREENING_COMPLETED, SHORTLISTED -> 1;
            case INTERVIEW_SCHEDULED, INTERVIEWED -> 2;
            // Phase 2.3: conditional selection is part of the Offer band —
            // formal offer hasn't been issued yet but the staff decision is made.
            case SELECTED_CONDITIONAL, OFFERED -> 3;
            // ACCEPTED leaves the Offer band immediately: the candidate has
            // signed, an Engagement was created, and they're now in the
            // post-offer "Hired" block. The frontend stepper substitutes the
            // engagement-derived final-stage label ("Onboarding"/"Active"/
            // "Completed"/"Blocked") so this band reads coherently with the
            // banner.
            case ACCEPTED, ONBOARDING, ACTIVE, HIRED, COMPLETED -> 4;
            // Exit statuses (REJECTED/WITHDRAWN/LAPSED/NO_SHOW) are not on
            // the stepper — callers gate by isExited() first.
            default -> -1;
        };
    }

    /** True for the four exit statuses; false for null and for in-funnel values. */
    public static boolean isExited(ApplicationStatus s) {
        return s != null && EXIT_STATUSES.contains(s);
    }

    /**
     * Gated transition table. {@code from -> { allowed to-values }}.
     *
     * Same-state self-transitions are treated as legal no-ops by the
     * guard; they are NOT listed here. Terminal states have an empty set —
     * nothing legal moves out of them.
     *
     * REJECTED and WITHDRAWN are reachable from EVERY non-terminal state
     * (you can reject/withdraw at any active stage).
     *
     * GAP_REPORT A3 (hard gate, no override): OFFERED is reachable ONLY from
     * {INTERVIEWED, SELECTED_CONDITIONAL}. Direct APPLIED/SHORTLISTED/
     * INTERVIEW_SCHEDULED → OFFERED jumps were previously legal and have
     * been removed — an interview decision is a prerequisite for an offer.
     * SELECTED_CONDITIONAL is reachable ONLY from INTERVIEWED.
     *
     * Enforced by {@code ApplicationService.transitionTo}. The override path
     * ({@code transitionToSystem}) bypasses this map for SYSTEM/ADMIN-only
     * use cases (demo backfill, manual corrections) but still audits.
     */
    public static final Map<ApplicationStatus, Set<ApplicationStatus>> LEGAL_TRANSITIONS = Map.ofEntries(
            Map.entry(ApplicationStatus.APPLIED, EnumSet.of(
                    ApplicationStatus.SCREENING_SENT,
                    ApplicationStatus.SHORTLISTED,
                    // ERM Phase 2 — decision-flow holding states.
                    ApplicationStatus.HOLD,
                    ApplicationStatus.INFO_REQUESTED,
                    // GAP A3: OFFERED removed — must go via INTERVIEWED first.
                    ApplicationStatus.REJECTED,
                    ApplicationStatus.WITHDRAWN)),
            // ERM Phase 2 — HOLD legal exits: resume to APPLIED, or terminal
            // exit. Cannot leap straight to SHORTLISTED; ERM must resume first
            // so the row re-enters the inbox queue cleanly.
            Map.entry(ApplicationStatus.HOLD, EnumSet.of(
                    ApplicationStatus.APPLIED,
                    ApplicationStatus.REJECTED,
                    ApplicationStatus.WITHDRAWN)),
            // ERM Phase 2 — INFO_REQUESTED legal exits: intern provides info
            // (→ APPLIED), or terminal exit.
            Map.entry(ApplicationStatus.INFO_REQUESTED, EnumSet.of(
                    ApplicationStatus.APPLIED,
                    ApplicationStatus.REJECTED,
                    ApplicationStatus.WITHDRAWN)),
            // Phase 2.1 screening. SHORTLISTED is NOT reachable from
            // SCREENING_SENT — the recruiter has to wait for completion (or
            // explicitly reject/withdraw). Once completed, SHORTLISTED and
            // INTERVIEW_SCHEDULED both become legal so staff can advance or
            // skip the shortlist step entirely.
            Map.entry(ApplicationStatus.SCREENING_SENT, EnumSet.of(
                    ApplicationStatus.SCREENING_COMPLETED,
                    ApplicationStatus.REJECTED,
                    ApplicationStatus.WITHDRAWN)),
            Map.entry(ApplicationStatus.SCREENING_COMPLETED, EnumSet.of(
                    ApplicationStatus.SHORTLISTED,
                    ApplicationStatus.INTERVIEW_SCHEDULED,
                    ApplicationStatus.REJECTED,
                    ApplicationStatus.WITHDRAWN)),
            Map.entry(ApplicationStatus.SHORTLISTED, EnumSet.of(
                    ApplicationStatus.INTERVIEW_SCHEDULED,
                    // GAP A3: OFFERED removed — must go via INTERVIEWED first.
                    ApplicationStatus.REJECTED,
                    ApplicationStatus.WITHDRAWN)),
            Map.entry(ApplicationStatus.INTERVIEW_SCHEDULED, EnumSet.of(
                    ApplicationStatus.INTERVIEWED,
                    ApplicationStatus.INTERVIEW_SCHEDULED, // legal re-schedule
                    // GAP A3: OFFERED removed — must reach INTERVIEWED first
                    // (an interview decision is a prerequisite).
                    ApplicationStatus.NO_SHOW,
                    ApplicationStatus.REJECTED,
                    ApplicationStatus.WITHDRAWN)),
            Map.entry(ApplicationStatus.INTERVIEWED, EnumSet.of(
                    // Phase 2.3 — usual advance is via conditional selection
                    // first, but direct INTERVIEWED → OFFERED is kept legal so
                    // teams that skip the conditional step still work.
                    ApplicationStatus.SELECTED_CONDITIONAL,
                    ApplicationStatus.OFFERED,
                    ApplicationStatus.REJECTED,
                    ApplicationStatus.WITHDRAWN)),
            // Phase 2.3 — conditional employment confirmation has been sent; the
            // formal offer (OfferService.send) is the legal next advance.
            Map.entry(ApplicationStatus.SELECTED_CONDITIONAL, EnumSet.of(
                    ApplicationStatus.OFFERED,
                    ApplicationStatus.REJECTED,
                    ApplicationStatus.WITHDRAWN)),
            Map.entry(ApplicationStatus.OFFERED, EnumSet.of(
                    ApplicationStatus.ACCEPTED,
                    ApplicationStatus.REJECTED,
                    ApplicationStatus.WITHDRAWN,
                    ApplicationStatus.LAPSED)),
            Map.entry(ApplicationStatus.ACCEPTED, EnumSet.of(
                    ApplicationStatus.ONBOARDING,
                    ApplicationStatus.HIRED,
                    ApplicationStatus.REJECTED,
                    ApplicationStatus.WITHDRAWN)),
            Map.entry(ApplicationStatus.ONBOARDING, EnumSet.of(
                    ApplicationStatus.ACTIVE,
                    ApplicationStatus.HIRED,
                    ApplicationStatus.REJECTED,
                    ApplicationStatus.WITHDRAWN)),
            Map.entry(ApplicationStatus.HIRED, EnumSet.of(
                    ApplicationStatus.ACTIVE,
                    ApplicationStatus.COMPLETED,
                    ApplicationStatus.REJECTED,
                    ApplicationStatus.WITHDRAWN)),
            Map.entry(ApplicationStatus.ACTIVE, EnumSet.of(
                    ApplicationStatus.COMPLETED,
                    ApplicationStatus.REJECTED,
                    ApplicationStatus.WITHDRAWN)),
            Map.entry(ApplicationStatus.COMPLETED, EnumSet.noneOf(ApplicationStatus.class)),
            Map.entry(ApplicationStatus.NO_SHOW, EnumSet.of(
                    ApplicationStatus.REJECTED,
                    ApplicationStatus.WITHDRAWN)),
            // Terminal exits — nothing moves out.
            Map.entry(ApplicationStatus.REJECTED, EnumSet.noneOf(ApplicationStatus.class)),
            Map.entry(ApplicationStatus.WITHDRAWN, EnumSet.noneOf(ApplicationStatus.class)),
            Map.entry(ApplicationStatus.LAPSED, EnumSet.noneOf(ApplicationStatus.class)));
}
