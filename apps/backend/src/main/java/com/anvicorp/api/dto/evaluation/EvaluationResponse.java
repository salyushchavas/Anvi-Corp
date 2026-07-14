package com.anvicorp.api.dto.evaluation;

import com.anvicorp.api.enums.EvaluationRecommendation;
import com.anvicorp.api.enums.EvaluationStatus;
import com.anvicorp.api.enums.EvaluationType;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvaluationResponse {
    private UUID id;
    private UUID internCandidateId;
    private String internName;
    private UUID engagementId;
    private UUID evaluatorId;
    private String evaluatorName;

    private EvaluationType type;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private Integer overallRating;
    private String strengths;
    private String areasForImprovement;
    private String comments;
    private EvaluationRecommendation recommendation;
    private EvaluationStatus status;

    private Instant createdAt;
    private Instant finalizedAt;
    private Instant updatedAt;

    private List<EvaluationRubricScoreResponse> rubric;
    /** Null when no self-review row exists yet. */
    private EvaluationSelfReviewResponse selfReview;
    /** Null when the requester doesn't need context (intern read / list view). */
    private EvaluationContextResponse context;
}
