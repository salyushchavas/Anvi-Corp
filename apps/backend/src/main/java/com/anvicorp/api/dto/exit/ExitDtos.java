package com.anvicorp.api.dto.exit;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Phase 8 — DTO surface for the exit-record + feedback flows. Records grouped
 * here to keep the package light; the controller maps entities to these.
 */
public final class ExitDtos {

    private ExitDtos() {}

    public record CreateExitRecordRequest(
            UUID internLifecycleId,
            String exitType,
            LocalDate exitDate,
            String exitReason,
            String internVisibleSummary,
            Boolean rehireEligible
    ) {}

    public record PatchExitRecordRequest(
            String exitReason,
            String internVisibleSummary,
            Boolean rehireEligible,
            String internalNotes
    ) {}

    public record ChecklistRequest(
            Boolean done,
            String summary
    ) {}

    public record LinkFinalEvaluationRequest(
            UUID evaluationId
    ) {}

    public record SubmitFeedbackRequest(
            Integer overallRating,
            Integer learningRating,
            Integer mentorshipRating,
            Integer workEnvironmentRating,
            String whatWentWell,
            String whatCouldImprove,
            Boolean wouldRecommend,
            String additionalComments
    ) {}

    /** Full ERM/staff projection of an exit record. */
    public record ExitRecordResponse(
            UUID id,
            UUID internLifecycleId,
            UUID internId,
            String internName,
            String internEmail,
            String exitType,
            LocalDate exitDate,
            String exitReason,
            UUID initiatedById,
            String initiatedByName,
            UUID finalEvaluationId,
            Boolean rehireEligible,
            Boolean accessRevocationDone,
            Instant accessRevocationAttemptedAt,
            String accessRevocationSummary,
            Boolean finalDocumentsArchived,
            String internVisibleSummary,
            String internalNotes,
            Instant amendedAt,
            Instant createdAt,
            Instant updatedAt
    ) {}

    /** Intern-safe projection — internalNotes always stripped. */
    public record ExitRecordInternView(
            UUID id,
            String exitType,
            LocalDate exitDate,
            String exitReason,
            UUID finalEvaluationId,
            Boolean rehireEligible,
            String internVisibleSummary,
            Instant createdAt
    ) {}

    public record ExitFeedbackResponse(
            UUID id,
            UUID exitRecordId,
            Integer overallRating,
            Integer learningRating,
            Integer mentorshipRating,
            Integer workEnvironmentRating,
            String whatWentWell,
            String whatCouldImprove,
            Boolean wouldRecommend,
            String additionalComments,
            Instant submittedAt
    ) {}

    /** Intern home/summary card stats. */
    public record ExitSummaryResponse(
            String exitType,
            LocalDate exitDate,
            long durationDays,
            long projectsCompleted,
            long evaluationsCount,
            Double averageScore,
            long timesheetsApproved,
            java.math.BigDecimal totalApprovedHours,
            boolean feedbackSubmitted,
            String internVisibleSummary,
            UUID finalEvaluationId
    ) {}

    public record PendingExitItem(
            UUID internLifecycleId,
            UUID internId,
            String internName,
            String internEmail,
            Instant hiredAt,
            List<String> signals
    ) {}

    public record ExitRecordListPage(
            List<ExitRecordResponse> items,
            int page,
            int pageSize,
            long totalElements,
            int totalPages
    ) {}
}
