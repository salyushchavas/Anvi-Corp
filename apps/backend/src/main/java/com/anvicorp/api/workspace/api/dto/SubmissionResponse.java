package com.anvicorp.api.workspace.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Submission row + scalar fields. The file list is fetched separately via
 * {@code GET /submissions/{id}/files} to keep the row payload small.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubmissionResponse(
        UUID id,
        UUID projectId,
        Integer submissionNumber,
        Instant submittedAt,
        UUID submittedBy,
        Instant reviewedAt,
        UUID reviewerId,
        String reviewOutcome,
        String reviewReason,
        Integer fileCount
) {}
