package com.anvicorp.api.erm.documents;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTOs for the ERM Document Gallery — a per-intern view of every
 * onboarding document the intern uploaded. Built on top of the existing
 * document_packets / document_tasks / documents tables — no new
 * storage. Pure-overwrite semantics: each task row points at the LATEST
 * Document via {@code uploaded_file_id}; prior files are soft-deleted
 * on re-upload so only the current version is reachable.
 */
public final class DocumentGalleryDtos {

    private DocumentGalleryDtos() {}

    /** One row per intern on the gallery list. */
    public record InternRow(
            UUID lifecycleId,
            UUID userId,
            String employeeId,
            String fullName,
            String email,
            String activeStatus,
            Instant hiredAt,
            Instant endedAt,
            int packetCount,
            int totalTasks,
            int uploadedCount,
            int pendingTasks,
            int revisionRequestedTasks,
            int acceptedTasks,
            Instant lastUploadAt
    ) {}

    public record InternListResponse(
            List<InternRow> items,
            int total
    ) {}

    /** Per-intern gallery detail — every packet + every task + the
     *  latest uploaded file metadata. */
    public record InternGalleryDetail(
            UUID lifecycleId,
            UUID userId,
            String employeeId,
            String fullName,
            String email,
            String activeStatus,
            List<PacketView> packets
    ) {}

    public record PacketView(
            UUID packetId,
            String status,
            Instant assignedAt,
            Instant internSubmittedAt,
            Instant completedAt,
            String customInstructions,
            List<TaskView> tasks
    ) {}

    public record TaskView(
            UUID taskId,
            String documentKey,
            String documentTitle,
            String category,
            String sensitivity,
            String status,            // PENDING / SUBMITTED / UNDER_REVIEW / ACCEPTED / REJECTED / RESEND_REQUESTED / WAIVED
            Integer version,
            FileRef uploadedFile,     // null when nothing uploaded yet
            Instant submittedAt,
            Instant reviewedAt,
            String reviewReasonCode,
            String reviewComments
    ) {}

    public record FileRef(
            UUID documentId,
            String fileName,
            String mimeType,
            long fileSize,
            Instant uploadedAt
    ) {}
}
