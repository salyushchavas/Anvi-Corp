package com.anvicorp.api.dto.supervised;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SubmitAssignmentRequest {

    @NotBlank(message = "submissionText is required")
    private String submissionText;

    private String submissionLink;
}
