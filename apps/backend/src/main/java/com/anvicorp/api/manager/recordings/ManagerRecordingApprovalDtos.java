package com.anvicorp.api.manager.recordings;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTOs for the Manager-side recording approval queue. Feeds the
 * "Recording Approvals" page: one row per evaluation with a recording
 * currently in {@code PENDING_APPROVAL} (or optionally
 * {@code REVISION_REQUESTED} — for audit trace). The playback URL is
 * lazy — the frontend fetches it via the existing
 * {@code /api/v1/evaluation-recordings/{id}/download-url} endpoint when
 * the manager clicks Play, so this DTO stays lightweight.
 */
public final class ManagerRecordingApprovalDtos {

    private ManagerRecordingApprovalDtos() {}

    public record PendingRow(
            UUID evaluationId,
            UUID recordingDocumentId,
            String monthYear,
            String evaluationType,
            /** P1 | P2 | P1_P2. */
            String scope,
            UUID internUserId,
            String internName,
            String employeeId,
            UUID projectId,
            String projectTitle,
            UUID evaluatorId,
            String evaluatorName,
            String fileName,
            Long fileSizeBytes,
            Instant uploadedAt,
            /** PENDING_APPROVAL | REVISION_REQUESTED. */
            String approvalStatus,
            /** Hours since the recording was uploaded — for FIFO ordering. */
            long hoursWaiting
    ) {}

    public record PendingListResponse(
            List<PendingRow> items,
            int totalCount
    ) {}

    public record RequestRevisionRequest(
            /** Manager's notes — shown verbatim to the evaluator on the
             *  compose page. Required, min 10 chars so the note is
             *  actionable rather than a bare "fix it". */
            String notes
    ) {}
}
