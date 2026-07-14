package com.anvicorp.api.dto.evaluation;

import com.anvicorp.api.enums.RubricCriterion;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvaluationRubricScoreResponse {
    private UUID id;
    private RubricCriterion criterion;
    private Integer score;
    private String note;
}
