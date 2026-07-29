package com.anvicorp.api.evaluator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Evaluator Phase 2 — request/response DTOs for the monthly evaluation
 *  workflow (schedule → start → draft → publish → acknowledge → amend). */
public final class EvaluationWorkflowDtos {

    private EvaluationWorkflowDtos() {}

    // ── Requests ──────────────────────────────────────────────────────────

    public record ScheduleRequest(
            UUID internLifecycleId,
            Instant scheduledFor,
            Integer durationMinutes,
            String topic,
            String agenda,
            String timezone
    ) {}

    /** Phase 4 — Final evaluation scheduling. Same shape as ScheduleRequest
     *  for now; kept as a distinct record so the controller and validation
     *  paths can diverge cleanly (Final has its own ExitRecord guard). */
    public record ScheduleFinalRequest(
            UUID internLifecycleId,
            Instant scheduledFor,
            Integer durationMinutes,
            String topic,
            String agenda,
            String timezone
    ) {}

    public record SaveDraftRequest(
            Integer technicalSkillsScore,
            Integer communicationScore,
            Integer professionalismScore,
            Integer learningApplicationScore,
            String strengths,
            String areasForImprovement,
            String comments,
            String recommendation,
            String internalNotes
    ) {}

    public record AcknowledgeRequest(String internResponse) {}

    public record AmendRequest(
            String amendmentReason,
            Integer technicalSkillsScore,
            Integer communicationScore,
            Integer professionalismScore,
            Integer learningApplicationScore,
            String strengths,
            String areasForImprovement,
            String comments,
            String recommendation
    ) {}

    // ── Responses ─────────────────────────────────────────────────────────

    /** Evaluator-facing detail — includes internal_notes + zoom_start_url
     *  + recommendation. Used on /careers/evaluator/evaluations/{id}. */
    public record EvaluatorEvaluationDetail(
            UUID evaluationId,
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String internEmail,
            String employeeId,
            String technology,
            String evaluationType,
            String status,
            int version,
            LocalDate periodStart,
            LocalDate periodEnd,
            Instant scheduledFor,
            Integer durationMinutes,
            String timezone,
            String zoomJoinUrl,
            String zoomStartUrl,
            String zoomMeetingId,            // Phase 2 — String since meeting providers differ on id shape
            Integer technicalSkillsScore,
            Integer communicationScore,
            Integer professionalismScore,
            Integer learningApplicationScore,
            Double averageScore,
            String strengths,
            String areasForImprovement,
            String comments,
            String recommendation,
            String internalNotes,
            Instant publishedAt,
            Instant internAcknowledgedAt,
            String internResponse,
            Instant amendedAt,
            String amendmentReason,
            List<AmendmentEntry> amendments,
            /** Optional S3-backed session recording. Non-null means a
             *  recording has been attached; playback URL is minted by
             *  {@code GET /api/v1/evaluation-recordings/{id}/download-url}. */
            UUID recordingDocumentId,
            /** Project the recording pertains to (evaluator-selected on
             *  the compose page). Null for MONTHLY evaluations that haven't
             *  been linked yet. */
            UUID linkedProjectId,
            /** Compact per-project context for POST_PROJECT evaluations.
             *  Non-null when {@code linkedProjectId} resolves to a project.
             *  Full context (deliverables, requirements, review notes) is
             *  available on {@code GET /api/v1/evaluator/projects/{id}}. */
            LinkedProjectSummary linkedProject,
            /** Manager approval gate for the attached recording.
             *  PENDING_APPROVAL / APPROVED / REVISION_REQUESTED. Null
             *  when no recording is attached yet. */
            String recordingApprovalStatus,
            /** Manager's notes when REVISION_REQUESTED — shown to the
             *  evaluator on the compose page so they know what to fix. */
            String recordingRevisionNotes,
            /** P1 / P2 / P1_P2 — the recording's scope. Null when no
             *  recording is attached. */
            String recordingScope
    ) {}

    /**
     * Compact linked-project block on the evaluator's evaluation detail.
     * Enough to render the "which project are we evaluating" chip + basic
     * metadata inline; the compose page can pull richer detail from
     * {@code /api/v1/evaluator/projects/{id}} on demand.
     */
    public record LinkedProjectSummary(
            UUID projectId,
            String title,
            String techStack,
            String status,                  // ProjectStatus name
            Integer projectNumber,
            String monthYear,
            LocalDate dueDate,
            Instant submittedAt,
            Instant completedAt
    ) {}

    /** Intern-facing detail — no internal_notes, no zoom_start_url, no
     *  amendment internal trace. Strips field-level PII appropriately. */
    public record InternEvaluationView(
            UUID evaluationId,
            String evaluatorName,
            String evaluationType,
            String status,
            int version,
            LocalDate periodStart,
            LocalDate periodEnd,
            Instant scheduledFor,
            String zoomJoinUrl,
            Integer technicalSkillsScore,
            Integer communicationScore,
            Integer professionalismScore,
            Integer learningApplicationScore,
            Double averageScore,
            String strengths,
            String areasForImprovement,
            String comments,
            String recommendation,
            Instant publishedAt,
            Instant internAcknowledgedAt,
            String internResponse,
            Instant amendedAt
    ) {}

    public record InternEvaluationRow(
            UUID evaluationId,
            String evaluatorName,
            String evaluationType,
            String status,
            int version,
            Instant publishedAt,
            Instant internAcknowledgedAt,
            Integer averageScoreInt,    // rounded for the list chip
            String recommendation
    ) {}

    public record AmendmentEntry(
            UUID amendmentId,
            UUID amendedByUserId,
            String amendedByName,
            String amendmentReason,
            int previousVersion,
            int newVersion,
            Instant amendedAt
    ) {}

    public record PendingEvaluationsResponse(
            List<ScheduledRow> scheduledAndInProgress,
            List<AwaitingAckRow> awaitingAcknowledgment
    ) {}

    public record ScheduledRow(
            UUID evaluationId,
            UUID internLifecycleId,
            String internName,
            String employeeId,
            String evaluationType,
            String status,
            Instant scheduledFor,
            Integer durationMinutes,
            String zoomJoinUrl
    ) {}

    public record AwaitingAckRow(
            UUID evaluationId,
            UUID internLifecycleId,
            String internName,
            String employeeId,
            String evaluationType,
            Instant publishedAt,
            int daysPending
    ) {}
}
