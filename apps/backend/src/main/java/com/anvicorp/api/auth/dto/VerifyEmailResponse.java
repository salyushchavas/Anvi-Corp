package com.anvicorp.api.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record VerifyEmailResponse(
        boolean emailVerified,
        String applicantId,
        String message
) {}
