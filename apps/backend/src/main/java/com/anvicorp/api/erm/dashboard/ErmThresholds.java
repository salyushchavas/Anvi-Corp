package com.anvicorp.api.erm.dashboard;

/**
 * Centralized SLA thresholds used by both KPI urgency calculations and
 * exception detection. Single source of truth so tuning is one edit.
 */
public final class ErmThresholds {

    private ErmThresholds() {}

    /** Application sitting in pending review longer than this is urgent. */
    public static final int APPLICATION_OVERDUE_DAYS = 5;

    /** Offer expiring within this many hours bumps urgency. */
    public static final int OFFER_EXPIRY_URGENT_HOURS = 24;

    /** Onboarding packet age before it counts as overdue (Phase 4 SLA). */
    public static final int ONBOARDING_OVERDUE_DAYS = 7;

    /** Onboarding packet age before urgent escalation. */
    public static final int ONBOARDING_URGENT_DAYS = 14;

    /** Days past start_date before I-9 Section 2 / E-Verify is overdue (federal 3-business-day rule, approximated). */
    public static final int I9_EVERIFY_OVERDUE_DAYS = 3;

    /** Days past intern activation without a project assigned. */
    public static final int NO_PROJECT_OVERDUE_DAYS = 5;

    /** Days without a weekly trainer meeting before flagging. */
    public static final int TRAINER_MEETING_MISSING_DAYS = 7;

    /** Days since last MONTHLY evaluation before flagging overdue. */
    public static final int EVAL_OVERDUE_DAYS = 35;

    /** Days past exit_date before incomplete checklist becomes a flag. */
    public static final int EXIT_CHECKLIST_PENDING_DAYS = 7;

    /** Days a rejected onboarding document can sit without resubmission. */
    public static final int DOC_REJECTED_FOLLOWUP_DAYS = 7;

    /** Quick-action badge defaults to red when count exceeds this. */
    public static final int QUICK_ACTION_RED_THRESHOLD = 5;
}
