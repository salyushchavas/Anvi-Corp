package com.anvicorp.api.recordings;

import java.time.Instant;
import java.util.UUID;

/** DTOs shared between the standalone-upload pages (ERM interview +
 *  Evaluator evaluation) and their presign endpoints. */
public final class StandaloneRecordingDtos {

    private StandaloneRecordingDtos() {}

    public record InterviewPresignRequest(
            UUID internLifecycleId,
            String fileName,
            String contentType,
            Long fileSize
    ) {}

    public record PresignResponse(
            String uploadUrl,
            UUID documentId,
            String storageKey,
            Instant expiresAt
    ) {}

    public record DownloadUrlResponse(
            String downloadUrl,
            Instant expiresAt
    ) {}

    /** Evaluator standalone upload — pre-flight resolves the target
     *  {@code InternEvaluation} (creating a DRAFT one if none exists)
     *  and auto-retrieves the project title from
     *  (internLifecycleId, monthYear, scope). Returns the evaluationId
     *  so the frontend can then hit the existing
     *  {@code /api/v1/evaluator/evaluations/{id}/recording/presign-upload}
     *  endpoint. */
    public record EvaluatorPrepareRequest(
            UUID internLifecycleId,
            /** yyyy-MM (e.g. "2026-07"). */
            String monthYear,
            /** P1 | P2 | P1_P2. */
            String scope,
            /** Manual override for the project name. Optional; the
             *  service auto-retrieves it from the projects table when
             *  scope is P1 or P2. Required when scope=P1_P2 or when
             *  no matching project row exists yet. */
            String projectNameOverride
    ) {}

    public record EvaluatorPrepareResponse(
            UUID evaluationId,
            /** Auto-retrieved from the projects table, or the caller's
             *  override, or null when neither is available. */
            String projectName,
            boolean projectNameAutoRetrieved,
            String scope,
            String monthYear
    ) {}

    public record ActiveEvalueeOption(
            UUID lifecycleId,
            UUID internUserId,
            String internName,
            String employeeId
    ) {}
}
