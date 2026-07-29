package com.anvicorp.api.evaluator.recordings;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * DTOs for the evaluation-recording upload / gallery flow. Mirrors the
 * shape of the interview-recording DTOs (see
 * {@code ErmInterviewDtos.RecordingPresignUploadRequest} /
 * {@code RecordingPresignUploadResponse}) so the frontend can reuse the
 * same {@code RecordingUploader} widget pattern verbatim.
 */
public final class EvaluationRecordingDtos {

    private EvaluationRecordingDtos() {}

    // ── Upload ────────────────────────────────────────────────────────────

    public record PresignUploadRequest(
            String fileName,
            String contentType,
            Long fileSize
    ) {}

    public record PresignUploadResponse(
            String uploadUrl,
            UUID documentId,
            String storageKey,
            Instant expiresAt
    ) {}

    /**
     * Save the completed upload against the evaluation. The evaluator
     * picks WHICH project this recording pertains to at the same time
     * (the intern may have multiple concurrent assignments); the picked
     * id populates {@link com.anvicorp.api.entity.InternEvaluation#getLinkedProjectId()}
     * so the gallery can group MONTHLY evaluations by project.
     * {@code linkedProjectId} may be null when the evaluator explicitly
     * ticks "General / no project". {@code scope} distinguishes a P1
     * recording, a P2 recording, and a combined P1+P2 session — the
     * gallery renders a "P1+P2" combined folder for the last case.
     */
    public record SaveRecordingRequest(
            UUID recordingDocumentId,
            UUID linkedProjectId,
            /** P1 | P2 | P1_P2 — optional; null preserves the prior value. */
            String scope
    ) {}

    // ── Project selector ─────────────────────────────────────────────────

    /** One row per project the intern has an assignment on. Populates the
     *  Session Recording section's project dropdown on the compose page. */
    public record InternProjectOption(
            UUID projectId,
            String title,
            String status,
            LocalDate assignmentDate,
            LocalDate dueDate
    ) {}

    // ── Gallery ──────────────────────────────────────────────────────────

    /**
     * One recording row for the ERM + Manager gallery. The frontend
     * groups by {@code monthYear} → {@code internUserId} → {@code projectId}.
     */
    public record RecordingRow(
            UUID evaluationId,
            UUID recordingDocumentId,
            /** {@code yyyy-MM} format derived from {@code period_start} —
             *  the canonical grouping bucket. */
            String monthYear,
            LocalDate periodStart,
            LocalDate periodEnd,
            String evaluationType,
            UUID internUserId,
            String internName,
            String employeeId,
            UUID projectId,
            String projectTitle,
            /** P1 | P2 | P1_P2 — for the gallery folder grouping. */
            String scope,
            UUID evaluatorId,
            String evaluatorName,
            String fileName,
            String mimeType,
            Long fileSizeBytes,
            Instant uploadedAt,
            /** PENDING_APPROVAL / APPROVED / REVISION_REQUESTED. */
            String approvalStatus,
            /** Manager's notes when REVISION_REQUESTED (else null). */
            String revisionNotes,
            /** UUID of the manager who last transitioned this recording. */
            UUID approvedByUserId,
            Instant approvedAt
    ) {}

    public record GalleryResponse(
            List<RecordingRow> items
    ) {}

    public record DownloadUrlResponse(
            String downloadUrl,
            Instant expiresAt
    ) {}
}
