package com.anvicorp.api.dto.evaluation;

import com.anvicorp.api.enums.EvaluationType;
import com.anvicorp.api.enums.RubricCriterion;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateEvaluationRequest {

    @NotNull
    private UUID candidateId;

    @NotNull
    private EvaluationType type;

    private LocalDate periodStart;
    private LocalDate periodEnd;

    /** Optional pre-filled rubric scores. The form posts them all on save. */
    private List<RubricScoreInput> rubric;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RubricScoreInput {
        @NotNull
        private RubricCriterion criterion;
        @NotNull
        private Integer score;
        private String note;
    }
}
