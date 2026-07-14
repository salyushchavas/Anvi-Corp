package com.anvicorp.api.dto.evaluation;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvaluationSelfReviewResponse {
    private UUID id;
    private String reflection;
    private Integer selfOverallRating;
    private Integer selfTechnicalRating;
    private Integer selfGrowthRating;
    private Instant submittedAt;
}
