package com.anvicorp.api.erm.exit;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** ERM Phase 7 — DTO surface for the Exit operations center. */
public final class ErmExitDtos {

    private ErmExitDtos() {}

    public record ErmExitRow(
            UUID exitRecordId,
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String employeeId,
            String exitType,
            LocalDate exitDate,
            LocalDate lastWorkingDay,
            int totalItems,
            int completeItems,
            int pendingItems,
            int waivedItems,
            int notApplicableItems,
            int daysSinceInitiate,
            boolean managerOverridden,
            String overallState                  // ACTIVE | READY_TO_CLOSE | CLOSED
    ) {}

    public record ErmExitListPage(
            List<ErmExitRow> items,
            int page,
            int pageSize,
            long totalElements,
            int totalPages
    ) {}

    public record ReadyToExitRow(
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String employeeId,
            int daysActive,
            String suggestedExitType,
            List<String> signals                 // human-readable bullets
    ) {}

    public record ReadyToExitListPage(
            List<ReadyToExitRow> items,
            int page,
            int pageSize,
            long totalElements,
            int totalPages
    ) {}

    public record ChecklistItemRow(
            UUID id,
            String itemKey,
            String status,
            Instant completedAt,
            UUID completedById,
            String completedByName,
            String note,
            Instant updatedAt
    ) {}

    public record AssetStatus(
            Boolean laptopReturned,
            Boolean badgeReturned,
            Boolean buildingAccessRemoved,
            Boolean parkingPassReturned,
            Boolean keysReturned,
            String otherNotes
    ) {}

    public record ErmExitDetail(
            UUID exitRecordId,
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String internEmail,
            String employeeId,
            String exitType,
            LocalDate exitDate,
            LocalDate lastWorkingDay,
            String reasonCode,
            String exitReason,                   // legacy free text
            String internVisibleSummary,
            String internalNotes,                // ERM-only
            String assetStatusJson,              // ERM-only — serialised AssetStatus
            String finalTimesheetStatus,
            Boolean rehireEligible,
            Boolean accessRevocationDone,
            String accessRevocationSummary,
            Instant accessRevocationCompletedAt,
            Boolean finalDocumentsArchived,
            Instant finalDocumentsArchivedAt,
            UUID finalEvaluationId,
            UUID managerOverrideId,
            String managerOverrideReason,
            Instant managerOverrideAt,
            UUID initiatedById,
            String initiatedByName,
            Instant createdAt,
            Instant updatedAt,
            int daysSinceInitiate,
            List<ChecklistItemRow> checklist,
            boolean readyToClose,
            boolean feedbackSubmitted
    ) {}

    public record InitiateExitRequest(
            UUID internLifecycleId,
            String exitType,
            LocalDate exitDate,
            LocalDate lastWorkingDay,
            String reasonCode,
            String reasonText,
            String internVisibleSummary,
            Boolean rehireEligible,
            String finalTimesheetStatus
    ) {}

    public record ChecklistUpdateRequest(
            String status,
            String note
    ) {}

    public record LinkEvaluationRequest(UUID evaluationId) {}

    public record AssetStatusRequest(AssetStatus status) {}

    public record ManagerOverrideRequest(
            String reasonCode,
            String reasonText
    ) {}

    public record InternalNoteRequest(String note) {}

    public record ReasonCodeOption(
            String code, String label, boolean requiresFreeText) {}

    public record ReasonCodeGroup(String family, List<ReasonCodeOption> options) {}

    /** ERM read-side view of intern's submitted exit feedback. */
    public record FeedbackView(
            UUID id,
            UUID exitRecordId,
            UUID internUserId,
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
}
