package com.anvicorp.api.trainer.dashboard;

/**
 * Trainer Phase 1 — constants that drive urgent-count + alert thresholds
 * across the Trainer dashboard. Kept here rather than scattered as
 * magic numbers so the doc §4 "what's urgent" rules are explicit.
 */
public final class TrainerThresholds {

    private TrainerThresholds() {}

    /** Project due-date inside this window counts as URGENT on the
     *  "projects due this week" KPI. */
    public static final int PROJECT_DUE_URGENT_HOURS = 24;

    /** Submission waiting longer than this without a trainer decision
     *  flips the "submissions pending review" KPI urgent count. */
    public static final int SUBMISSION_PENDING_URGENT_HOURS = 48;

    /** Submission still pending past this window counts on the
     *  "overdue feedback" KPI URGENT slice. */
    public static final int FEEDBACK_OVERDUE_URGENT_HOURS = 72;

    /** Active intern with no scheduled / completed meeting in this many
     *  days triggers the "missing weekly meeting" alert. */
    public static final int MEETING_MISSING_DAYS = 7;

    /** Cache TTL for the dashboard payload. */
    public static final long DASHBOARD_CACHE_TTL_SECONDS = 60L;

    /** Default timezone for "today" + "this week" boundaries — matches
     *  the Anvi HQ office zone. Phase 2 can promote this to a per-user
     *  preference if needed. */
    public static final String DEFAULT_TIMEZONE = "America/Chicago";
}
