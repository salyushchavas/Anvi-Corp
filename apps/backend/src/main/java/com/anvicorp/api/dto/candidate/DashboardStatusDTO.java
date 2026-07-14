package com.anvicorp.api.dto.candidate;

import lombok.*;

import java.time.Instant;
import java.util.List;

/**
 * Change 2 — richer status payload for the candidate dashboard's "Your
 * Journey" panel. Produced by {@code CandidateDashboardService.getDashboardStatus}
 * and served at {@code GET /api/v1/candidate/dashboard-status}.
 *
 * <p>Two concerns in one round-trip:
 * <ul>
 *   <li>{@code timeline} — every applicable lifecycle step the intern/applicant
 *       moves through, with a stable {@code key} + visible state. Non-applicable
 *       steps are kept and marked {@code SKIPPED} so the intern sees what was
 *       not required, not just what was done.</li>
 *   <li>{@code recentUpdates} — last-10 user-visible notifications addressed
 *       to this user (read from {@code sent_notifications}). Drives the
 *       collapsible "Recent activity" feed beneath the timeline.</li>
 * </ul>
 *
 * <p>{@code nextStep} is shape-compatible with the existing
 * {@link CandidateDashboardResponse.NextStep} field so the frontend can reuse
 * the same hero card component without a second type.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DashboardStatusDTO {

    /** APPLIED | SCREENING | INTERVIEW | OFFER | ONBOARDING | HIRED | ACTIVE | COMPLETED | REJECTED. */
    private String overallStage;

    /** Active "next move" card. May be null when nothing is queued (rare). */
    private CandidateDashboardResponse.NextStep nextStep;

    /** Full ordered timeline; never null, always populated. */
    private List<TimelineStepDTO> timeline;

    /** Last-10 user-visible updates, newest first. */
    private List<RecentUpdateDTO> recentUpdates;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TimelineStepDTO {
        /** Stable key — APPLIED, SCREENING, INTERVIEW, OFFER_ACCEPTED, I9_SECTION1, I9_SECTION2, I983, E_VERIFY, ONBOARDING_TASKS, HR_ACTIVATION, FIRST_DAY, ACTIVE_WEEKS, COMPLETED. */
        private String key;
        /** Human-readable label rendered in the timeline row. */
        private String label;
        /** DONE | IN_PROGRESS | WAITING | BLOCKED | NOT_STARTED | SKIPPED. */
        private String status;
        /** Set on DONE rows when the timestamp is known. */
        private Instant completedAt;
        /** Set on WAITING rows — who/what is blocking ("HR", "DSO", etc). */
        private String waitingFor;
        /** One-line plain-English explainer for the step. */
        private String description;
        /** True when the intern needs to do something to advance this step. */
        private boolean actionRequired;
        /** Deep link the intern should follow when {@code actionRequired}. */
        private String actionHref;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RecentUpdateDTO {
        private Instant timestamp;
        /** STATUS_CHANGE | COMPLIANCE | INTERVIEW | SYSTEM. */
        private String kind;
        /** Human-ready message. */
        private String message;
        /** HR | OPERATIONS | SYSTEM | TE | RM. */
        private String source;
    }
}
