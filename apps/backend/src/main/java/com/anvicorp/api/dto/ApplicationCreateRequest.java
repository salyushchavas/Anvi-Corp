package com.anvicorp.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class ApplicationCreateRequest {

    @NotNull(message = "jobPostingId is required")
    private UUID jobPostingId;

    @NotNull(message = "resumeId is required")
    private UUID resumeId;

    /** Phase 2 — optional motivation typed in the apply modal. ≤ 500 chars. */
    @Size(max = 500, message = "statementOfInterest must be ≤ 500 characters")
    private String statementOfInterest;
}
