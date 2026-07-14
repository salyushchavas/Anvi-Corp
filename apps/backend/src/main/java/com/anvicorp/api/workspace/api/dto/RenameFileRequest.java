package com.anvicorp.api.workspace.api.dto;

import jakarta.validation.constraints.NotBlank;

public record RenameFileRequest(
        @NotBlank String fromPath,
        @NotBlank String toPath
) {}
