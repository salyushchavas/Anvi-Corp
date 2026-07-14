package com.anvicorp.api.judge0.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/playground/run}.
 * Bean-validation handles the hard limits — the controller doesn't need
 * to re-check.
 */
public record RunCodeRequest(
        @NotBlank @Size(max = 50_000, message = "sourceCode must be at most 50,000 characters")
        String sourceCode,

        @NotNull @Min(value = 1, message = "languageId must be positive")
        Integer languageId,

        @Size(max = 50_000, message = "stdin must be at most 50,000 characters")
        String stdin
) {}
