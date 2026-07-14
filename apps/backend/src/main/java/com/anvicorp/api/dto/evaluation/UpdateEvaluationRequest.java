package com.anvicorp.api.dto.evaluation;

import com.anvicorp.api.enums.EvaluationRecommendation;
import lombok.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Supervisor → save draft. Null fields are left untouched. {@code rubric},
 * when non-null, REPLACES the existing rubric rows for this evaluation.
 *
 * Blocked at service level when the evaluation is FINALIZED.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateEvaluationRequest {
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private Integer overallRating;
    private String strengths;
    private String areasForImprovement;
    private String comments;
    private EvaluationRecommendation recommendation;
    private List<CreateEvaluationRequest.RubricScoreInput> rubric;
}
