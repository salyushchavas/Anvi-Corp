package com.anvicorp.api.evaluator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Evaluator Phase 3 — request/response DTOs for the I-983 evaluation
 *  workflow (schedule → start → draft → publish → acknowledge →
 *  mark-DSO-submitted → amend). Parallel to {@link EvaluationWorkflowDtos}
 *  but with distinct I-983-specific fields. */
public final class I983WorkflowDtos {

    private I983WorkflowDtos() {}

    // ── Requests ──────────────────────────────────────────────────────────

    public record ScheduleI983Request(
            UUID internLifecycleId,
            /** INITIAL_PLAN | ANNUAL_REVIEW | FINAL_REVIEW */
            String evaluationType,
            Instant scheduledFor,
            Integer durationMinutes,
            LocalDate periodStartDate,
            LocalDate periodEndDate,
            String agenda
    ) {}

    public record SaveDraftI983Request(
            String trainingObjectivesProgress,
            String trainingSupervisionProvided,
            String trainingEvaluationOutcomes,
            String objectivesAchieved,
            String supervisorAssessment
    ) {}

    public record AcknowledgeI983Request(
            String studentTypedSignature,
            String internResponse
    ) {}

    public record MarkDsoSubmittedRequest(
            /** EMAIL_TO_DSO | PORTAL_UPLOAD | IN_PERSON | MAIL */
            String submissionMethod,
            String submissionNotes
    ) {}

    public record AmendI983Request(
            String amendmentReason,
            String trainingObjectivesProgress,
            String trainingSupervisionProvided,
            String trainingEvaluationOutcomes,
            String objectivesAchieved,
            String supervisorAssessment
    ) {}

    // ── Responses ─────────────────────────────────────────────────────────

    public record I983PlanContext(
            UUID planId,
            String status,
            LocalDate trainingStartDate,
            LocalDate trainingEndDate,
            String universityName,
            String trainingGoalsAndObjectives,
            String performanceEvaluationMethod,
            String supervisorName,
            String supervisorEmail
    ) {}

    /** Evaluator-facing detail — full I-983 evaluation with plan context. */
    public record EvaluatorI983Detail(
            UUID evaluationId,
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String internEmail,
            String employeeId,
            String evaluationType,
            String status,
            int version,
            LocalDate periodStartDate,
            LocalDate periodEndDate,
            String trainingObjectivesProgress,
            String trainingSupervisionProvided,
            String trainingEvaluationOutcomes,
            String objectivesAchieved,
            String supervisorAssessment,
            String evaluatorName,
            Instant publishedAt,
            Instant acknowledgedAt,
            String studentTypedSignature,
            String internResponse,
            Instant dsoSubmittedAt,
            String dsoSubmissionMethod,
            String dsoSubmissionNotes,
            Instant amendedAt,
            String amendmentReason,
            I983PlanContext planContext
    ) {}

    /** Intern-facing detail — same content but no internal_notes; signature
     *  block is rendered client-side from the typed-signature field. */
    public record InternI983View(
            UUID evaluationId,
            String evaluatorName,
            String evaluationType,
            String status,
            int version,
            LocalDate periodStartDate,
            LocalDate periodEndDate,
            String trainingObjectivesProgress,
            String trainingSupervisionProvided,
            String trainingEvaluationOutcomes,
            String objectivesAchieved,
            String supervisorAssessment,
            Instant publishedAt,
            Instant acknowledgedAt,
            String studentTypedSignature,
            String internResponse,
            Instant dsoSubmittedAt,
            String dsoSubmissionMethod,
            Instant amendedAt,
            I983PlanContext planContext
    ) {}

    /** Row on the Evaluator's I-983 list page. Combines "due soon" interns
     *  (no active evaluation) with existing evaluation rows so the same
     *  shape powers all 3 tabs. */
    public record I983ListRow(
            /** Either "INTERN_ELIGIBLE" (no evaluation yet) or
             *  "EVALUATION" (existing row). */
            String entryKind,
            UUID evaluationId,
            UUID internLifecycleId,
            String internName,
            String employeeId,
            String evaluationType,
            String status,
            Instant scheduledFor,
            Instant publishedAt,
            Instant acknowledgedAt,
            Instant dsoSubmittedAt,
            LocalDate trainingStartDate,
            LocalDate trainingEndDate,
            LocalDate nextDueDate,
            Integer daysUntilDue,
            boolean planExists
    ) {}

    public record I983ListResponse(
            List<I983ListRow> dueSoon,
            List<I983ListRow> inProgress,
            List<I983ListRow> completed
    ) {}

    public record InternI983Row(
            UUID evaluationId,
            String evaluatorName,
            String evaluationType,
            String status,
            int version,
            LocalDate periodStartDate,
            LocalDate periodEndDate,
            Instant publishedAt,
            Instant acknowledgedAt,
            Instant dsoSubmittedAt
    ) {}
}
