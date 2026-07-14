package com.anvicorp.api.erm.documents;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ERM Phase 8.2 — DTO surface for the document-packet workflow + intern
 * documents page. The previous template-management types (DocumentTemplateDto
 * + Create/UpdateTemplateRequest + the DocumentTemplatePage list page) are
 * gone — templates are now the static {@link SkyzenDocument} enum, served
 * from {@code frontend/public/document-templates/}.
 */
public final class DocumentDtos {

    private DocumentDtos() {}

    // ── Assign packet ───────────────────────────────────────────────────

    public record AssignPacketRequest(
            UUID internLifecycleId,
            List<SkyzenDocument> selectedDocumentKeys,
            String customInstructions,
            Map<SkyzenDocument, String> perDocumentInstructions
    ) {}

    // ── Packets ─────────────────────────────────────────────────────────

    public record DocumentPacketRow(
            UUID packetId,
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String internEmployeeId,
            String status,
            int totalTasks,
            int acceptedTasks,
            int submittedTasks,
            int pendingTasks,
            int rejectedTasks,
            int waivedTasks,
            Instant assignedAt,
            Instant completedAt,
            /** Phase 1.6 — the intern has explicitly handed the packet off
             *  for verification. Surface a "Submitted, awaiting verification"
             *  badge on the ERM listing when true. */
            boolean internLocked,
            Instant internSubmittedAt
    ) {}

    public record DocumentPacketListPage(
            List<DocumentPacketRow> items,
            int page, int pageSize, long totalElements, int totalPages
    ) {}

    public record TaskSummary(
            UUID taskId,
            SkyzenDocument documentKey,
            String templateTitle,
            String category,
            String sensitivity,
            String templatePublicUrl,
            String status,
            Integer version,
            Instant submittedAt,
            Instant reviewedAt,
            String reviewReasonCode,
            String reviewComments,
            UUID uploadedFileId,
            String uploadedFileName,
            String taskInstructions
    ) {}

    public record DocumentPacketDetail(
            UUID packetId,
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String internEmail,
            String internEmployeeId,
            String status,
            String customInstructions,
            Instant assignedAt,
            Instant firstSubmissionAt,
            Instant allSubmittedAt,
            Instant completedAt,
            Instant cancelledAt,
            String cancellationReason,
            List<TaskSummary> tasks,
            boolean readyToClose
    ) {}

    // ── Review queue + task detail ───────────────────────────────────────

    public record DocumentTaskRow(
            UUID taskId,
            UUID packetId,
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            SkyzenDocument documentKey,
            String templateTitle,
            String category,
            String status,
            Integer version,
            Instant submittedAt,
            long hoursWaiting
    ) {}

    public record DocumentTaskListPage(
            List<DocumentTaskRow> items,
            int page, int pageSize, long totalElements, int totalPages
    ) {}

    /**
     * One row in the by-intern grouped review queue — backs the
     * "list of people who have things waiting" view that replaced
     * the flat per-document table. {@code oldestSubmittedAt} powers
     * the "X hours waiting" badge and the queue ordering.
     */
    public record InternReviewQueueRow(
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            long pendingCount,
            Instant oldestSubmittedAt,
            long oldestHoursWaiting
    ) {}

    public record InternReviewQueuePage(
            List<InternReviewQueueRow> items,
            int page, int pageSize, long totalElements, int totalPages
    ) {}

    public record ReviewEventEntry(
            UUID id,
            UUID actorUserId,
            String actorName,
            String eventType,
            String previousStatus,
            String newStatus,
            String reasonCode,
            String comments,
            Instant createdAt
    ) {}

    public record DocumentTaskDetail(
            UUID taskId,
            UUID packetId,
            SkyzenDocument documentKey,
            String templateTitle,
            String category,
            String sensitivity,
            String templatePublicUrl,
            String status,
            Integer version,
            String taskInstructions,
            UUID uploadedFileId,
            String uploadedFileName,
            Long uploadedFileSize,
            String uploadedFileMime,
            Instant submittedAt,
            Instant reviewedAt,
            UUID reviewedById,
            String reviewedByName,
            String reviewReasonCode,
            String reviewComments,
            String internalNote,             // ERM-only — stripped from intern DTO
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            List<ReviewEventEntry> history,
            /** Pass 2 verify-after-download gate. Null until any ERM
             *  hits {@code GET /document-review/tasks/{id}/file}; once
             *  set, the UI enables the "Mark verified" decision and the
             *  server permits ACCEPT. Stays set across reviews (downloads
             *  are accumulative). */
            Instant lastDownloadedAt,
            Integer downloadCount
    ) {}

    public record ReviewTaskRequest(
            String decision,                 // ACCEPT | REJECT | RESEND_REQUEST
            String reasonCode,
            String reasonText,
            String ermComments,
            String internalNote
    ) {}

    public record BulkReviewRequest(
            List<UUID> taskIds,
            String decision,                 // ACCEPT only — bulk-reject requires per-task comments
            String reasonCode,
            String reasonText
    ) {}

    public record BulkReviewResult(
            int accepted,
            int skipped,
            List<BulkSkipReason> skippedReasons
    ) {}

    public record BulkSkipReason(UUID taskId, String reason) {}

    public record ReasonCodeOption(String code, String label, boolean requiresFreeText) {}

    public record ReasonCodeGroup(String family, List<ReasonCodeOption> options) {}

    // ── Intern-facing variants (no ERM-only fields) ──────────────────────

    public record InternTaskView(
            UUID taskId,
            SkyzenDocument documentKey,
            String templateTitle,
            String description,
            String category,
            String sensitivity,
            String templatePublicUrl,
            String status,
            Integer version,
            String taskInstructions,
            Instant submittedAt,
            Instant reviewedAt,
            String reviewReasonCode,         // OK to expose — used to render "Reason: X" label
            String reviewComments,           // shown verbatim
            String uploadedFileName
    ) {}

    public record InternPacketView(
            UUID packetId,
            String status,
            String customInstructions,
            Instant assignedAt,
            Instant completedAt,
            List<InternTaskView> tasks,
            int totalTasks,
            int acceptedTasks,
            /** Phase 1.6 — explicit intern handoff signal. While true, the
             *  intern cannot upload or replace files; ERM owns the packet
             *  for review. Reset to false when ERM rejects / requests
             *  resend on any task. */
            boolean internLocked,
            Instant internSubmittedAt,
            /** Convenience: number of tasks still PENDING (i.e. not yet
             *  uploaded). When 0 the intern can submit. */
            int pendingTasks
    ) {}
}
