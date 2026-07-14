package com.anvicorp.api.dto.doubt;

import java.time.Instant;
import java.util.UUID;

/**
 * Single-file DTO bag for the Doubt-Session feature. Records over
 * classes — same idiom as the rest of the new controllers.
 */
public final class DoubtDtos {

    private DoubtDtos() {}

    // ── Intern → backend ─────────────────────────────────────────────────

    public record CreateDoubtRequest(
            UUID projectId,
            UUID projectAssignmentId,
            String text,
            UUID attachmentDocumentId
    ) {}

    // ── Trainer → backend ────────────────────────────────────────────────

    public record ReplyRequest(String reply) {}

    public record ScheduleSessionRequest(
            Instant scheduledFor,
            Integer durationMinutes,
            String timezone,
            String topic,
            String agenda
    ) {}

    // ── Backend → frontend ───────────────────────────────────────────────

    /**
     * Wire shape for a doubt row. The {@code zoomStartUrl} is included
     * ONLY when the caller is the trainer (host); the intern-side
     * surface omits it.
     */
    public record DoubtResponse(
            UUID id,
            UUID internUserId,
            String internName,
            UUID trainerUserId,
            String trainerName,
            UUID projectId,
            String projectTitle,
            UUID projectAssignmentId,
            String text,
            UUID attachmentDocumentId,
            String attachmentFileName,
            String status,
            String trainerReply,
            Instant repliedAt,
            String repliedByName,
            // ── Session (omit start url for intern ───────────────────
            String zoomMeetingId,
            String zoomJoinUrl,
            String zoomStartUrl,
            String zoomPassword,
            Instant sessionScheduledFor,
            Integer sessionDurationMinutes,
            String sessionTimezone,
            // ── Resolved ─────────────────────────────────────────────
            Instant resolvedAt,
            String resolvedByName,
            // ── Timestamps ───────────────────────────────────────────
            Instant createdAt,
            Instant updatedAt
    ) {}
}
