package com.anvicorp.api.dto.supervised;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * At-a-glance summary for the intern's My Work page. Every nullable field is
 * either a value or {@code null} — never "missing" or absent — so the frontend
 * can render "—" deterministically. Counts default to 0; {@code totalApprovedHours}
 * defaults to {@link BigDecimal#ZERO} when there are no APPROVED timesheets.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupervisedOverviewResponse {

    private BigDecimal totalApprovedHours;

    /** Count of assignments not yet REVIEWED (i.e. ASSIGNED + IN_PROGRESS + SUBMITTED). */
    private long openAssignments;

    /** Count of REVIEWED assignments. */
    private long reviewedAssignments;

    /** Soonest SCHEDULED session with {@code scheduledAt >= now}, or null. */
    private NextEvaluation nextEvaluation;

    /** Most recent COMPLETED session by completedAt, or null. */
    private LatestEvaluation latestEvaluation;

    /** {@code Candidate.assignedEvaluator.fullName}, or null. */
    private String evaluatorName;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class NextEvaluation {
        private LocalDateTime scheduledAt;
        private String evaluatorName;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LatestEvaluation {
        private Integer overallRating;
        private Instant completedAt;
    }
}
