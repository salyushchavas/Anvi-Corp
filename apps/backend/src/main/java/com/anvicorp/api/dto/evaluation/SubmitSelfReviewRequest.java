package com.anvicorp.api.dto.evaluation;

import lombok.*;

/**
 * Intern → fill or update the self-review attached to an I-983 evaluation.
 * Allowed only when the parent evaluation's type is {@code I983_12MO} or
 * {@code I983_FINAL}, and only while the evaluation is still DRAFT (the
 * supervisor reads it before finalizing).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubmitSelfReviewRequest {
    private String reflection;
    /** 1–5. Optional. */
    private Integer selfOverallRating;
    private Integer selfTechnicalRating;
    private Integer selfGrowthRating;
}
