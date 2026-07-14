package com.anvicorp.api.workspace.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ReturnSubmissionRequest(
        @NotBlank String reason
) {}
