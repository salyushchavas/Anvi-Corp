package com.anvicorp.api.application;

import com.anvicorp.api.enums.EngagementStatus;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Phase 3 step 1 — single source of truth for the {@link EngagementStatus}
 * lifecycle. Mirrors the Phase 1.1a pattern: defined here, NOT enforced
 * anywhere yet. The {@code EngagementService.transitionTo} guard lands in
 * step 2 and consumers come later.
 *
 *  PENDING_COMPLIANCE        → READY_TO_START | BLOCKED_NO_AUTHORIZATION | TERMINATED
 *  READY_TO_START            → ACTIVE | TERMINATED
 *  ACTIVE                    → COMPLETED | TERMINATED
 *  COMPLETED / TERMINATED / BLOCKED_NO_AUTHORIZATION  → terminal
 *
 * Same-state self-transitions are treated as legal no-ops by the (forthcoming)
 * guard — they are NOT listed here. Terminal states have an empty allow-set.
 *
 * "Exited" semantics differ from ApplicationLifecycle: an engagement that
 * reaches COMPLETED is a successful end (NOT an exit). Exits are TERMINATED
 * (cut short) and BLOCKED_NO_AUTHORIZATION (never started).
 */
public final class EngagementLifecycle {

    private EngagementLifecycle() {}

    /** Statuses outside the normal-success funnel — surfaced as {@code isExited=true} on the wire. */
    private static final Set<EngagementStatus> EXIT_STATUSES = EnumSet.of(
            EngagementStatus.TERMINATED,
            EngagementStatus.BLOCKED_NO_AUTHORIZATION);

    /**
     * True for early-exit states; false for null, in-funnel values, and the
     * successful-end COMPLETED.
     */
    public static boolean isExited(EngagementStatus s) {
        return s != null && EXIT_STATUSES.contains(s);
    }

    /**
     * Gated transition table — defined now, enforced in step 2.
     *
     * BLOCKED_NO_AUTHORIZATION is terminal here at step 1; an HR-driven
     * unblock path can widen the table in a later phase without breaking
     * any callers (additive change to the allow-set).
     */
    public static final Map<EngagementStatus, Set<EngagementStatus>> LEGAL_TRANSITIONS = Map.ofEntries(
            Map.entry(EngagementStatus.PENDING_COMPLIANCE, EnumSet.of(
                    EngagementStatus.READY_TO_START,
                    EngagementStatus.BLOCKED_NO_AUTHORIZATION,
                    EngagementStatus.TERMINATED)),
            Map.entry(EngagementStatus.READY_TO_START, EnumSet.of(
                    EngagementStatus.ACTIVE,
                    EngagementStatus.TERMINATED)),
            Map.entry(EngagementStatus.ACTIVE, EnumSet.of(
                    EngagementStatus.COMPLETED,
                    EngagementStatus.TERMINATED)),
            // Terminals — nothing moves out.
            Map.entry(EngagementStatus.COMPLETED, EnumSet.noneOf(EngagementStatus.class)),
            Map.entry(EngagementStatus.TERMINATED, EnumSet.noneOf(EngagementStatus.class)),
            Map.entry(EngagementStatus.BLOCKED_NO_AUTHORIZATION, EnumSet.noneOf(EngagementStatus.class)));
}
