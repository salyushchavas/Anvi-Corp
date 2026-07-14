package com.anvicorp.api.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SetGithubUsernameRequest(
        @NotBlank
        @Size(max = 100)
        @Pattern(
                regexp = "^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}$",
                message = "Invalid GitHub username. Must be 1-39 alphanumeric characters "
                        + "or hyphens, not starting or ending with a hyphen and no "
                        + "consecutive hyphens."
        )
        String githubUsername
) {}
