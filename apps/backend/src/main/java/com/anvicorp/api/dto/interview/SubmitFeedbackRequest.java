package com.anvicorp.api.dto.interview;

import com.anvicorp.api.enums.InterviewRecommendation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubmitFeedbackRequest {

    @NotNull
    @Min(1)
    @Max(5)
    private Integer overallRating;

    @Min(1)
    @Max(5)
    private Integer technicalRating;

    @Min(1)
    @Max(5)
    private Integer communicationRating;

    private String strengths;

    private String concerns;

    @NotNull
    private InterviewRecommendation recommendation;
}
