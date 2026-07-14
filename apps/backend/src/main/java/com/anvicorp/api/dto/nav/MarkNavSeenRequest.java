package com.anvicorp.api.dto.nav;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MarkNavSeenRequest(
        @NotBlank @Size(max = 64) String key
) {}
