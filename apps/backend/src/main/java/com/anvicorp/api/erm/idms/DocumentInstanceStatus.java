package com.anvicorp.api.erm.idms;

import java.util.Set;

/**
 * IDMS Phase 2 — living-document lifecycle.
 *
 * <pre>
 *  DRAFT ──ERM fills+signs+sends──▶ SENT_TO_INTERN
 *          ▲                              │
 *          │                              │ intern fills+signs+submits
 *          │                              ▼
 *          │                       INTERN_SUBMITTED
 *          │                     ┌────┴────┐
 *          │            ERM verify│         │ERM return (with reason)
 *          │            + finalize│         ▼
 *          │                      ▼      RETURNED
 *          │                 VERIFIED     ▲   │
 *          │                     │        │   │ intern fixes + resubmits
 *          │                     ▼        └───┘
 *          │                 FINALIZED
 *          │                     │
 *          └──── SUPERSEDED ◀────┤     REVOKED (from any post-DRAFT state
 *                                          while the intern hasn't started)
 *                              VOIDED (SUPER_ADMIN escape hatch)
 * </pre>
 */
public enum DocumentInstanceStatus {
    /** ERM is filling their fields + signing. */
    DRAFT,
    /** ERM has sent — intern's turn. */
    SENT_TO_INTERN,
    /** Intern has filled + signed — awaiting ERM verification. */
    INTERN_SUBMITTED,
    /** ERM asked for corrections — intern's turn again. */
    RETURNED,
    /** ERM verified — ready to render the executed PDF. */
    VERIFIED,
    /** Executed PDF saved to vault — the terminal happy-path state. */
    FINALIZED,
    /** ERM revoked before the intern's start date. */
    REVOKED,
    /** A newer instance replaced this one in history. */
    SUPERSEDED,
    /** SUPER_ADMIN escape hatch — kept for symmetry with the funnel offers path. */
    VOIDED;

    /** States from which revocation is allowed (subject to the start-date gate). */
    public static final Set<DocumentInstanceStatus> REVOCABLE = Set.of(
            SENT_TO_INTERN, INTERN_SUBMITTED, RETURNED, VERIFIED, FINALIZED);

    /** Terminal states — no further transitions. */
    public static final Set<DocumentInstanceStatus> TERMINAL = Set.of(
            REVOKED, SUPERSEDED, VOIDED);

    /** States NOT counted by the "one live instance per (lifecycle, template)"
     *  partial UNIQUE — mirrors the WHERE clause on uk_di_lifecycle_template_open. */
    public static final Set<DocumentInstanceStatus> NOT_LIVE = Set.of(
            VOIDED, SUPERSEDED, REVOKED);
}
