package com.anvicorp.api.dto.supervised;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReviewAssignmentRequest {

    @NotBlank(message = "reviewNote is required")
    private String reviewNote;
}
