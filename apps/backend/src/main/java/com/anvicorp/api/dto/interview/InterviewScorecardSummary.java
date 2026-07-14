package com.anvicorp.api.dto.interview;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.anvicorp.api.enums.InterviewRecommendation;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 2.2 — slim scorecard view for the recruiter review screen. We only
 * surface what's needed for the advance-vs-reject decision: the dimension
 * ratings, the recommendation, the comments, and who/when submitted.
 *
 * Returned by {@code GET /api/v1/applications/{id}/scorecard} — null when no
 * interview on the application has feedback yet.
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InterviewScorecardSummary {
    UUID interviewId;
    UUID applicationId;
    Integer technicalRating;
    Integer communicationRating;
    Integer problemSolvingRating;
    Integer overallRating;
    InterviewRecommendation recommendation;
    String comments;
    String submittedByName;
    Instant submittedAt;
    Instant scheduledAt;
}
