package com.anvicorp.api.enums;

/**
 * Trainer Phase 0 — the four feedback decisions a Trainer can record on
 * a project submission (doc §9). Phase 3 wires these into the review
 * endpoint + per-decision status transitions + per-decision notification
 * fan-out.
 *
 * <ul>
 *   <li>{@link #ACCEPT} — doc "Complete" — moves project to COMPLETED;
 *       fires FEEDBACK_PUBLISHED template.</li>
 *   <li>{@link #REQUEST_REVISION} — doc "Revision required" — moves
 *       project to REVISION_REQUESTED; fires FEEDBACK_PUBLISHED template
 *       (decision variant).</li>
 *   <li>{@link #ESCALATE} — doc "Escalate" — project stays SUBMITTED;
 *       opens an ExceptionRecord of type TRAINER_ESCALATION; fires
 *       FEEDBACK_PUBLISHED template (decision variant).</li>
 *   <li>{@link #NO_ACTION_YET} — doc "No action yet" — project stays
 *       SUBMITTED; NO notification fired (doc §9 "Optional note" only).</li>
 * </ul>
 */
public enum ProjectReviewDecision {
    ACCEPT,
    REQUEST_REVISION,
    ESCALATE,
    NO_ACTION_YET
}
