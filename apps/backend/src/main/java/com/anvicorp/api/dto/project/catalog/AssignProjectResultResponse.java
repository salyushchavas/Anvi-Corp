package com.anvicorp.api.dto.project.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * Partial-success result for the multi-intern assign action — one entry
 * in {@link #assignments} per intern that succeeded, one entry in
 * {@link #failures} per intern that failed with a human-readable reason.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssignProjectResultResponse(
        List<Created> assignments,
        List<Failure> failures
) {
    public record Created(UUID assignmentId, UUID internId, String status) {}
    public record Failure(UUID internId, String reason) {}
}
