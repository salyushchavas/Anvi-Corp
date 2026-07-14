package com.anvicorp.api.erm.escalation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** ERM Phase 6 — DTO surface for the Escalations queue. */
public final class ErmEscalationDtos {

    private ErmEscalationDtos() {}

    public record ExceptionRow(
            UUID id,
            String exceptionType,
            String severity,
            String status,
            UUID subjectUserId,
            String subjectName,
            String subjectEmployeeId,
            UUID internLifecycleId,
            UUID assignedToId,
            String assignedToName,
            Instant openedAt,
            Instant lastSeenAt,
            Instant assignedAt,
            Instant resolvedAt,
            int ageDays,
            String subjectResourceType,
            UUID subjectResourceId,
            String payloadJson
    ) {}

    public record ExceptionListPage(
            List<ExceptionRow> items,
            int page,
            int pageSize,
            long totalElements,
            int totalPages
    ) {}

    public record EventLogEntry(
            UUID id,
            UUID actorUserId,
            String actorName,
            String eventType,
            String previousStatus,
            String newStatus,
            String reasonCode,
            String note,                  // ERM-only
            Instant createdAt
    ) {}

    public record ExceptionDetail(
            UUID id,
            String exceptionType,
            String severity,
            String status,
            UUID subjectUserId,
            String subjectName,
            String subjectEmail,
            String subjectEmployeeId,
            UUID internLifecycleId,
            UUID assignedToId,
            String assignedToName,
            UUID assignedById,
            UUID resolvedById,
            String resolutionReasonCode,
            String resolutionNote,        // ERM-only
            Instant openedAt,
            Instant lastSeenAt,
            Instant assignedAt,
            Instant resolvedAt,
            String subjectResourceType,
            UUID subjectResourceId,
            String payloadJson,
            int ageDays,
            List<EventLogEntry> history
    ) {}

    public record AssignRequest(UUID assigneeUserId) {}

    public record NoteRequest(String note) {}

    public record ResolutionRequest(
            String reasonCode,
            String reasonText,
            String resolutionNote
    ) {}

    public record DismissalRequest(
            String reasonCode,
            String reasonText,
            String dismissalNote
    ) {}

    public record BulkResolveRequest(
            List<UUID> ids,
            String reasonCode,
            String reasonText,
            String resolutionNote
    ) {}

    public record BulkDismissRequest(
            List<UUID> ids,
            String reasonCode,
            String reasonText,
            String dismissalNote
    ) {}

    public record BulkActionResult(
            int succeeded,
            int skipped,
            List<BulkSkipReason> skippedReasons
    ) {}

    public record BulkSkipReason(UUID id, String reason) {}

    public record ReasonCodeOption(String code, String label, boolean requiresFreeText) {}

    public record ReasonCodeGroup(String family, List<ReasonCodeOption> options) {}
}
