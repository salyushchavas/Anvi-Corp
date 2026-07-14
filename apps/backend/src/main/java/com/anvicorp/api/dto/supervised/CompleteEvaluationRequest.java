package com.anvicorp.api.dto.supervised;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CompleteEvaluationRequest {

    @NotNull(message = "overallRating is required")
    @Min(value = 1, message = "overallRating must be between 1 and 5")
    @Max(value = 5, message = "overallRating must be between 1 and 5")
    private Integer overallRating;

    private String strengths;

    private String areasForImprovement;

    private String notes;
}
