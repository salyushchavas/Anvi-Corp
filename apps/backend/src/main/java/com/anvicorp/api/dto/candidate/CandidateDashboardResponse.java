package com.anvicorp.api.dto.candidate;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate payload powering the redesigned candidate dashboard. All fields
 * are present on every response shape — empty lists / null nextStep / 0% are
 * the empty-state. The frontend never has to special-case "missing key".
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CandidateDashboardResponse {

    private String candidateName;

    /** % of name + phone + dateOfBirth + hasResume that are filled (0–100). */
    private int profileComplete;

    /** Most urgent next action, or null when nothing actionable is queued. */
    private NextStep nextStep;

    private List<ApplicationSummary> applications;

    private List<UpcomingItem> upcoming;

    private List<ActivityItem> recentActivity;

    /**
     * Phase 3 step 10 follow-up — the candidate's active engagement summary,
     * used by the per-application stepper to override the final-stage label
     * ("Onboarding" / "Active" / "Completed" / "Blocked") so it agrees with
     * the dashboard banner. Null when the candidate has no post-offer
     * engagement (pre-offer state, or accepted-but-no-engagement legacy).
     */
    private EngagementSummary engagement;

    /**
     * Post-offer compliance status panel — surfaces "HR has completed your I-9
     * verification" / "Awaiting HR" + E-Verify + I-983 (STEM_OPT only) to the
     * candidate so they know who owes what next. Null/empty pre-offer.
     */
    private List<ComplianceItem> compliance;

    /**
     * SPEC §3 — six-macro-step journey bar (Applied → Screening → Interview →
     * Offer → Onboarding → Hired). Only the current stage carries a non-empty
     * {@code subSteps} list; past stages collapse to "done", future stages to
     * "upcoming". Drives the reusable JourneyBar component. Always present.
     */
    private Journey journey;

    /**
     * SPEC §6 — resume status card (filename + uploadedAt of the default
     * resume). Null when the candidate hasn't uploaded one yet.
     */
    private ResumeInfo resume;

    /**
     * Phase-2 weekly cockpit — populated only when the engagement is ACTIVE.
     * Carries this week's material (with ack status), this week's report
     * status, this week's timesheet status, and the current authorization
     * snapshot. Null on the applicant face / pre-ACTIVE / post-ACTIVE.
     */
    private WeeklyCockpit weeklyCockpit;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class NextStep {
        /** OFFER | INTERVIEW | ONBOARDING | WORK | SHORTLISTED | APPLIED | PROFILE | BROWSE
         *  | AWAITING_SCREENING | AWAITING_DECISION | AWAITING_HR_I9 | AWAITING_EVERIFY
         *  | AWAITING_DSO | AWAITING_READY | WELCOME | EXITED */
        private String type;
        private String title;
        private String subtitle;
        private String ctaLabel;
        private String ctaHref;

        /**
         * SPEC §5 — true when the next move is someone else's (recruiter
         * scheduling, employer doing I-9 §2, supervisor reviewing). The
         * frontend renders these as a waiting-state hero (info border, no CTA)
         * instead of a primary action button.
         */
        private boolean isWaiting;

        /** Who/what we're waiting on. Free text, surfaced under the title. */
        private String waitingFor;

        /** Optional ETA — when the candidate should expect a response. */
        private java.time.Instant expectedBy;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ApplicationSummary {
        private UUID id;
        private String position;
        private String entityName;
        /** Real ApplicationStatus enum name. */
        private String status;
        /** 0..4 stage index in the candidate funnel; -1 for exited statuses. */
        private int stageIndex;
        private boolean isExited;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UpcomingItem {
        /** INTERVIEW | OFFER_EXPIRY | ONBOARDING_DUE | EVALUATION */
        private String type;
        private String title;
        private String subtitle;
        private Instant at;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ActivityItem {
        private String text;
        private Instant at;
    }

    /**
     * Engagement-derived overrides for the per-application stepper's final
     * (post-offer) stage. The frontend matches by {@code applicationId} and,
     * for the matched row, replaces the hardcoded "Hired" label and styling
     * with these values so the stepper reads coherently with the banner.
     */
    /**
     * Per-compliance-item state row. {@code kind} is the stable key the
     * frontend keys icons + deep-links off ("I9_SECTION_1", "I9_SECTION_2",
     * "EVERIFY", "I983"). {@code state} is the visible label state —
     * "NOT_STARTED" / "IN_PROGRESS" / "AWAITING_HR" / "COMPLETED" / "BLOCKED".
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ComplianceItem {
        private String kind;
        private String label;
        private String state;
        /** Human subtitle — e.g. "Verified by Casey Lee on May 22, 2026". */
        private String subtitle;
        /** Optional deep link to where the candidate can act or view detail. */
        private String href;
        private Instant completedAt;
    }

    /**
     * SPEC §3 — the six-macro-step journey bar payload. Stages are ordered
     * Applied → Screening → Interview → Offer → Onboarding → Hired. The
     * {@code currentStageKey} matches one of the {@code key} values in
     * {@code stages} (or is "EXITED" when every application has exited).
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Journey {
        /** Stable key of the current stage (one of stages[].key), or "EXITED". */
        private String currentStageKey;
        /** True when the journey has terminated (REJECTED/WITHDRAWN/LAPSED/NO_SHOW). */
        private boolean isExited;
        /** Always six entries for the applicant face. */
        private List<JourneyStage> stages;
    }

    /**
     * One macro step on the journey bar. Past stages have {@code state="done"}
     * and an empty {@code subSteps} list. The current stage carries the
     * expanded {@code subSteps} checklist. Future stages stay collapsed.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class JourneyStage {
        /** APPLIED | SCREENING | INTERVIEW | OFFER | ONBOARDING | HIRED */
        private String key;
        private String label;
        /** done | current | upcoming | blocked */
        private String state;
        /** Empty unless this stage is the current one. */
        private List<SubStep> subSteps;
    }

    /**
     * SPEC §4 — a single sub-step inside the current macro stage. Conditional
     * sub-steps (e.g. I-983 for STEM OPT) are filtered server-side based on
     * the engagement's work-auth track.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SubStep {
        /** Stable key — e.g. "PROFILE", "TAKE_SCREENING", "I9_SECTION_1". */
        private String key;
        private String label;
        /** done | current | upcoming | waiting | blocked */
        private String state;
        /** "you" | "recruiter" | "employer" | "supervisor" | "dso" | "system" | null */
        private String owner;
        /** Optional deep link (null for waiting-state rows). */
        private String href;
        /** Short human descriptor — "Submitted on May 19" / "Awaiting HR". */
        private String subtitle;
    }

    /** SPEC §6 — resume status card (3-up status cards row). */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ResumeInfo {
        private UUID id;
        private String fileName;
        private java.time.Instant uploadedAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EngagementSummary {
        /** Application id that owns this engagement (joins the per-app stepper). */
        private UUID applicationId;
        /** EngagementStatus enum name verbatim. */
        private String status;
        /** Label to render at stage 4 — "Onboarding" / "Active" / "Completed" / "Blocked". */
        private String finalStageLabel;
        /** Visual state — "current" / "completed" / "blocked". */
        private String finalStageState;
        private long onboardingTotal;
        private long onboardingCompleted;
    }

    // ── Phase-2 intern face — weekly cockpit ────────────────────────────────

    /**
     * The active intern's weekly cockpit. Only populated when the engagement
     * is in {@code ACTIVE}. Carries this week's material + report + timesheet
     * snapshot so the dashboard can render three "this week" status cards
     * without a per-card round-trip. Nullable sub-rows mean "not yet logged"
     * (the row renders as an action prompt).
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class WeeklyCockpit {
        /** The Monday this cockpit is keyed to (today's Monday, server clock). */
        private LocalDate weekStart;
        /** Most-recent RELEASED material visible to this intern. May be null. */
        private MaterialCard material;
        /** This week's report row keyed on (intern, weekStart). Null if not yet created. */
        private ReportCard report;
        /** This week's timesheet row keyed on (intern, weekStart). Null if not yet logged. */
        private TimesheetCard timesheet;
        /** Earliest known work-auth expiry from the I-9 / I-983 chain. Null if neither set. */
        private AuthorizationInfo authorization;
        /** Active project the intern should focus on this week. Null when none. */
        private ProjectCard project;
        /** Latest FINALIZED evaluation (read-only) or DRAFT I-983 self-review prompt. Null when none. */
        private EvaluationCard latestEvaluation;
    }

    /**
     * Periodic evaluation surfaced on the intern cockpit. Drives one of:
     *   - read-only celebration card when the supervisor finalized one
     *     (selfReviewPending == false)
     *   - amber action prompt when the supervisor created a DRAFT I-983 that
     *     wants the intern's reflection (selfReviewPending == true)
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EvaluationCard {
        private UUID id;
        /** MIDPOINT / FINAL / I983_12MO / I983_FINAL / CHECKPOINT. */
        private String type;
        /** DRAFT / FINALIZED. */
        private String status;
        /** 1–5; only populated when FINALIZED. */
        private Integer overallRating;
        private Instant finalizedAt;
        /** True when this is an I-983 DRAFT the intern still owes a self-review for. */
        private boolean selfReviewPending;
        private String href;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ProjectCard {
        private UUID id;
        private String title;
        /** NOT_STARTED / IN_PROGRESS / SUBMITTED / RETURNED / COMPLETED. */
        private String status;
        private LocalDate dueDate;
        private Integer progressPct;
        /** Surfaced when status == RETURNED so the intern sees what to fix next. */
        private String reviewNotes;
        private String href;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MaterialCard {
        private UUID id;
        private Integer weekNo;
        private String title;
        private Instant releaseDate;
        private boolean acknowledged;
        private Instant acknowledgedAt;
        private String href;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ReportCard {
        /** Null when the intern hasn't started a report for this week yet. */
        private UUID id;
        private LocalDate weekStart;
        /** DRAFT / SUBMITTED / RETURNED / APPROVED, or null when no row exists. */
        private String status;
        private Instant submittedAt;
        private Instant reviewedAt;
        private String reviewNotes;
        private String href;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TimesheetCard {
        /** Null when the intern hasn't logged hours for this week yet. */
        private UUID id;
        private LocalDate weekStart;
        /** DRAFT / SUBMITTED / APPROVED / REJECTED, or null when no row exists. */
        private String status;
        private BigDecimal hours;
        private String href;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AuthorizationInfo {
        /** Earliest of I9.workAuthExpirationDate and I983.optEndDate. */
        private LocalDate expirationDate;
        /** Days from today; negative when already expired. */
        private Integer daysUntilExpiry;
        /** Human label — "Work authorization" or "STEM OPT". */
        private String authType;
    }
}
