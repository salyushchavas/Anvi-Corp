package com.anvicorp.api.dto.qa;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record SignOffRequest(
        @Min(0) @Max(10) Integer marks,
        @Size(max = 4000) String remarks
) {}
