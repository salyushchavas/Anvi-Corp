package com.anvicorp.api.enums;

/**
 * Phase 3 — coarse employment-phase status on the {@code Engagement} entity.
 * Fine-grained compliance (I-9 section/E-Verify case/I-983 DSO) stays on the
 * individual compliance entities; Engagement only carries the broad phase.
 *
 *   PENDING_COMPLIANCE        Just-accepted offer; track-router seeded the
 *                             required pieces and is waiting on them.
 *   READY_TO_START            Track-required compliance complete; planned
 *                             start date not yet reached.
 *   ACTIVE                    Intern is working.
 *   COMPLETED                 Reached planned/actual end date successfully.
 *   TERMINATED                Ended early (withdrawal, policy break, etc.).
 *   BLOCKED_NO_AUTHORIZATION  Track router couldn't route — candidate isn't
 *                             authorised to work and is referred to HR/legal.
 *                             Treated as terminal at step 1; HR-driven
 *                             unblock policy lives in a later step.
 */
public enum EngagementStatus {
    PENDING_COMPLIANCE,
    READY_TO_START,
    ACTIVE,
    COMPLETED,
    TERMINATED,
    BLOCKED_NO_AUTHORIZATION
}
