package com.anvicorp.api.mail.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateDomainRequest(
        @NotBlank String name,
        String displayName
) {
}
