package com.anvicorp.api.dto.project.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Reviewer payload when bouncing an assignment back to the intern.
 * The reason is appended to the assignment's submission notes so the
 * intern sees why their work was returned.
 */
public record ReturnAssignmentRequest(
        @NotBlank
        @Size(min = 10, max = 2000)
        String reason
) {}
