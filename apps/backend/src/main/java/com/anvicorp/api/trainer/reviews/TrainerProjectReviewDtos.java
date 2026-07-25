package com.anvicorp.api.trainer.reviews;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Trainer Phase 3 — DTOs for the Pending Reviews queue + doc §9
 *  4-decision Feedback Form. */
public final class TrainerProjectReviewDtos {

    private TrainerProjectReviewDtos() {}

    public record PendingRow(
            UUID submissionId,
            UUID projectId,
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String projectTitle,
            String technologyArea,
            Instant submittedAt,
            long hoursWaiting,
            int version,
            String monthYear,
            Short projectNumber,
            LocalDate dueDate,
            String description,
            String linksJson
    ) {}

    public record PendingPage(
            List<PendingRow> items,
            int page,
            int pageSize,
            long totalElements,
            int totalPages
    ) {}

    public record SubmissionDetail(
            UUID submissionId,
            UUID projectId,
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String projectTitle,
            String technologyArea,
            String projectInstructions,
            Instant submittedAt,
            int version,
            String description,
            String linksJson,
            // prior versions for this project (newest first, excluding this row)
            List<PriorRound> priorRounds,
            // already-recorded feedback (when revisiting an EXISTING decision)
            Short technicalScore,
            Short communicationScore,
            String blockersNote,
            String nextAction,
            LocalDate nextActionDueDate,
            String reviewedLinksCsv,
            String trainerDecision,
            String trainerFeedback,
            String completionStatus,
            Instant reviewedAt,
            /** Optional intern-uploaded supporting file for this
             *  submission. Null when the intern didn't attach one.
             *  Download at
             *  {@code GET /api/v1/project-assignments/submissions/{submissionId}/attachment}. */
            AttachmentRef attachment
    ) {}

    public record AttachmentRef(
            UUID documentId,
            String fileName,
            String mimeType,
            Long fileSize
    ) {}

    public record PriorRound(
            UUID submissionId,
            Instant submittedAt,
            int version,
            String description,
            String trainerDecision,
            String trainerFeedback,
            Instant reviewedAt
    ) {}

    /** Doc §9 Feedback Form — 7 fields + the terminal decision. */
    public record SubmitFeedbackRequest(
            String completionStatus,    // Completed / Needs Revision / Incomplete / Blocked
            Short technicalScore,       // 1-5
            Short communicationScore,   // 1-5
            String trainerFeedback,     // doc "Code review notes"
            String blockersNote,
            String nextAction,          // REVISION / NEXT_PROJECT / EXTRA_TRAINING / ESCALATION / NONE
            LocalDate nextActionDueDate,
            String reviewedLinksCsv,
            String decision,            // ACCEPT / REQUEST_REVISION / ESCALATE / NO_ACTION_YET
            String escalationReason     // ≥ 50 chars when decision=ESCALATE
    ) {}
}
