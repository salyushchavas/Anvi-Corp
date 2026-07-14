package com.anvicorp.api.dto.supervised;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * At-a-glance dashboard for the Evaluator hub. Counts default to 0;
 * {@code averageRating} is null when the evaluator has no completed sessions.
 * The three lists are bounded (top-N) — the full lists live behind their own
 * endpoints.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvaluatorDashboardResponse {
    private long internsCount;
    /** SCHEDULED sessions with scheduledAt >= now. */
    private long upcomingSessionsCount;
    /** SUBMITTED assignments awaiting this evaluator's review. */
    private long pendingReviewsCount;
    /** Mean overallRating across COMPLETED sessions, or null. */
    private BigDecimal averageRating;
    private List<EvaluatorInternResponse> myInterns;
    private List<EvaluatorSessionResponse> upcomingSessions;
    private List<EvaluatorAssignmentResponse> pendingReviews;
}
