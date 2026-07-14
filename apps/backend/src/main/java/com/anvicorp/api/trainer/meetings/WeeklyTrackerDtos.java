package com.anvicorp.api.trainer.meetings;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Wire shapes for the trainer's weekly-sessions tracker. The tracker
 * payload is one round-trip: master grid (all interns × all weeks of
 * the requested month) plus the trainer's per-week metadata. The
 * frontend filters by intern for the per-intern view.
 */
public final class WeeklyTrackerDtos {

    private WeeklyTrackerDtos() {}

    public record WeekSlot(
            int weekNumber,
            LocalDate weekStart
    ) {}

    /**
     * Per-week status on a single intern's row.
     * {@code status} values (UI labels): PENDING, SCHEDULED, DONE, MISSED, CANCELLED.
     * - PENDING  ← no meeting whose UTC Monday matches {@code weekStart}
     * - SCHEDULED ← entity status SCHEDULED
     * - DONE     ← entity status COMPLETED
     * - MISSED   ← entity status NO_SHOW
     * - CANCELLED ← entity status CANCELLED
     */
    public record InternWeekCell(
            int weekNumber,
            LocalDate weekStart,
            String status,
            UUID meetingId,
            Instant scheduledFor,
            Integer durationMinutes,
            String timezone,
            String topic,
            String zoomJoinUrl,
            String zoomStartUrl       // host-only, surfaced because this endpoint is trainer-gated
    ) {}

    public record InternRow(
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String employeeId,
            int doneCount,
            int scheduledCount,
            int pendingCount,
            int missedCount,
            List<InternWeekCell> weeks
    ) {}

    public record TrackerResponse(
            int year,
            int month,
            Instant asOf,
            List<WeekSlot> weeks,
            List<InternRow> interns
    ) {}
}
