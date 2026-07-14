package com.anvicorp.api.dto.project.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EligibleInternResponse(
        UUID id,
        String fullName,
        String email,
        String githubUsername,
        LocalDate engagementStartDate
) {}
