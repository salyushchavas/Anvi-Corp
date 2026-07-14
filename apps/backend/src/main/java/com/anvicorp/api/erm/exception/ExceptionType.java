package com.anvicorp.api.erm.exception;

/** Phase 1 — 8 exception detection categories surfaced on the ERM Home. */
public enum ExceptionType {
    UNSIGNED_OFFER_OVERDUE,
    ONBOARDING_DOC_REJECTED,
    I9_EVERIFY_TIMING_RISK,
    NO_PROJECT_ASSIGNED,
    TRAINER_MEETING_MISSING,
    EVALUATION_OVERDUE,
    TIMESHEET_MISSING,
    EXIT_CHECKLIST_PENDING,
    /** ERM Phase 4 — new hire signed offer but reporting structure not assigned. */
    REPORTING_STRUCTURE_INCOMPLETE,
    /** ERM Phase 5 — work auth (EAD / I-20 / authorized_until) expiring within 30 days. */
    WORK_AUTH_EXPIRING_30,
    /** ERM Phase 5 — F-1 STEM OPT / CPT intern past required I-983 evaluation cadence. */
    I983_EVALUATION_OVERDUE,
    /** ERM Phase 5 — E-Verify case sitting in TENTATIVE_NONCONFIRMATION past expected_close_by. */
    EVERIFY_NONCONFIRMATION,
    /** ERM Phase 6 — last weekly meeting was NO_SHOW or none scheduled in last 7 days while active. */
    MISSED_TRAINER_MEETING,
    /** ERM Phase 6 — project IN_PROGRESS &gt; 75% of duration with no submissions. */
    LOW_PROJECT_PROGRESS,
    /** ERM Phase 6 — ≥2 consecutive timesheet REJECTIONS in the last 4 weeks. */
    REPEATED_TIMESHEET_REJECTION,
    /** ERM Phase 7 — intern is INACTIVE_INTERN &gt; 30 days but exit checklist
     *  still has ≥1 PENDING item (no manager override). */
    EXIT_OVERDUE,
    /** Trainer Phase 0 — Trainer escalated a project submission for
     *  ERM / Manager review (decision = ESCALATE in
     *  {@link com.anvicorp.api.enums.ProjectReviewDecision}). Detector
     *  method is registered in Trainer Phase 3 alongside the review flow;
     *  inert until then. */
    TRAINER_ESCALATION,
    /** Evaluator Phase 0 — intern hasn't acknowledged a PUBLISHED
     *  evaluation within 7 days of receipt. Detector ships in
     *  Evaluator Phase 2; inert until then. */
    EVALUATION_ACK_OVERDUE,
    /** Evaluator Phase 4 — intern has an ExitRecord (engagement
     *  wrapping up) but no FINAL evaluation has been scheduled or
     *  published. Detector reserved; inert today — surfaces on
     *  Reports + History pages and via best-effort scheduling. */
    FINAL_EVALUATION_DUE
}
