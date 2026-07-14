package com.anvicorp.api.dto.project.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LinkRepositoryRequest(
        @NotBlank @Size(max = 200) String repositoryName,

        @NotBlank @Size(max = 500)
        @Pattern(
                regexp = "^https?://github\\.com/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/?$",
                message = "repositoryUrl must look like https://github.com/<org>/<repo>"
        )
        String repositoryUrl
) {}
