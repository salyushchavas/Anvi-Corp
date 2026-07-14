package com.anvicorp.api.dto.project.catalog;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The {@code remarks} field is the canonical name going forward; the
 * legacy {@code notes} payload key is accepted via {@link JsonAlias} so
 * the existing frontend modal that still posts {@code notes} keeps
 * working unchanged.
 */
public record AssignProjectRequest(
        @NotNull UUID projectId,
        @NotEmpty List<UUID> internIds,
        @NotNull LocalDate assignmentDate,
        LocalDate dueDate,
        @JsonAlias({ "notes" })
        @Size(max = 2000) String remarks
) {}
