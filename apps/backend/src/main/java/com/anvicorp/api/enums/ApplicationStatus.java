package com.anvicorp.api.enums;

public enum ApplicationStatus {
    APPLIED,
    // ERM Phase 2 — decision-flow holding states. Both stay in the "Applied"
    // band of the 5-stage stepper; neither advances users.lifecycle_status.
    // INFO_REQUESTED → APPLIED on intern provide-info; HOLD → APPLIED on
    // ERM resume-from-hold.
    HOLD,
    INFO_REQUESTED,
    // Phase 2.1: optional lightweight web screening between Apply and Shortlist.
    // Both states sit in the "Shortlisted" band of the 5-stage stepper.
    SCREENING_SENT,
    SCREENING_COMPLETED,
    SHORTLISTED,
    INTERVIEW_SCHEDULED,
    INTERVIEWED,
    // Phase 2.3 — conditional selection. Staff confirm "you're selected,
    // pending the formal offer + compliance" off the 2.2 scorecard; the offer
    // follows. Sits in the Offer band of the 5-stage stepper.
    SELECTED_CONDITIONAL,
    OFFERED,
    ACCEPTED,
    // ── Phase 3 step 10 — DEPRECATED post-offer states ──────────────────────
    // The post-offer phase now lives on Engagement.status. These values stay
    // in the enum so existing audit rows + applications still deserialize;
    // new code should NOT write them. Dashboards read Engagement.status and
    // fall back to these only for candidates without an engagement yet.
    @Deprecated ONBOARDING,
    @Deprecated ACTIVE,
    @Deprecated HIRED,
    @Deprecated COMPLETED,
    REJECTED,
    WITHDRAWN,
    LAPSED,
    NO_SHOW
}
