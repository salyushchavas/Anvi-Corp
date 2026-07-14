package com.anvicorp.api.dto.interview;

import com.anvicorp.api.enums.InterviewRecommendation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

/**
 * Phase 2.2 — structured interview scorecard. Replaces the freeform
 * {@link SubmitFeedbackRequest}. All three dimension ratings are required +
 * bounded 1-5; the recommendation drives the conditional-selection decision.
 * Comments are optional but encouraged. Idempotent: the assigned interviewer
 * can resubmit to overwrite their own prior scorecard.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubmitScorecardRequest {

    @NotNull
    @Min(1)
    @Max(5)
    private Integer technicalRating;

    @NotNull
    @Min(1)
    @Max(5)
    private Integer communicationRating;

    @NotNull
    @Min(1)
    @Max(5)
    private Integer problemSolvingRating;

    @NotNull
    private InterviewRecommendation recommendation;

    private String comments;
}
