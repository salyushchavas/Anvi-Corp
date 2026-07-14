package com.anvicorp.api.trainer.meetings;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.entity.WeeklyMeeting;
import com.anvicorp.api.intern.WeeklyMeetingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Trainer Phase 3 — Weekly Meetings HTTP surface. Delegates the core
 *  state transitions to {@link WeeklyMeetingService} and adds the
 *  4-recipient notification fan-out on top. */
@RestController
@RequestMapping("/api/v1/trainer/weekly-meetings")
@RequiredArgsConstructor
@Slf4j
public class TrainerWeeklyMeetingController {

    private final WeeklyMeetingService meetingService;
    private final TrainerMeetingNotificationDispatcher notifier;

    public record ScheduleRequest(
            UUID internLifecycleId,
            String scheduledFor,    // ISO-8601 instant
            Integer durationMinutes,
            String timezone,
            String topic,
            String agenda,
            String recurrence       // "WEEKLY" or null
    ) {}

    public record RescheduleRequest(
            String newScheduledFor,
            Integer newDurationMinutes,
            String reason
    ) {}

    public record CompleteRequest(
            String attendance,      // "TRAINER+INTERN" / "TRAINER_ONLY" / free text
            String notes,           // discussion summary (≥ 50 chars on the wire)
            String actionItems,
            String blockers
    ) {}

    public record MarkMissedRequest(
            String missedBy,        // "INTERN" / "TRAINER" / "BOTH"
            String reason           // ≥ 20 chars
    ) {}

    public record CancelRequest(String reason) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public List<Map<String, Object>> list(
            @RequestParam(required = false) UUID internLifecycleId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @AuthenticationPrincipal User caller) {
        List<WeeklyMeeting> rows = internLifecycleId != null
                ? meetingService.listForLifecycle(internLifecycleId)
                : meetingService.listForTrainer(caller);
        return rows.stream()
                .filter(m -> status == null || status.isBlank()
                        || status.equalsIgnoreCase(m.getStatus()))
                .filter(m -> fromDate == null || fromDate.isBlank()
                        || m.getScheduledFor() == null
                        || !m.getScheduledFor().isBefore(
                                LocalDate.parse(fromDate)
                                        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant()))
                .filter(m -> toDate == null || toDate.isBlank()
                        || m.getScheduledFor() == null
                        || !m.getScheduledFor().isAfter(
                                LocalDate.parse(toDate).plusDays(1)
                                        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant()))
                .map(TrainerWeeklyMeetingController::toDto)
                .toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public Map<String, Object> get(@PathVariable UUID id,
                                    @AuthenticationPrincipal User caller) {
        return toDto(meetingService.requireForReader(id, caller));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public Map<String, Object> schedule(@RequestBody ScheduleRequest req,
                                         @AuthenticationPrincipal User caller) {
        WeeklyMeeting m = meetingService.schedule(
                req.internLifecycleId(),
                req.scheduledFor() != null ? Instant.parse(req.scheduledFor()) : null,
                req.durationMinutes(),
                req.timezone(),
                req.topic(),
                req.agenda(),
                req.recurrence(),
                caller);
        notifier.dispatchScheduled(m, caller, null);
        return toDto(m);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public Map<String, Object> reschedule(@PathVariable UUID id,
                                           @RequestBody RescheduleRequest req,
                                           @AuthenticationPrincipal User caller) {
        WeeklyMeeting before = meetingService.requireForReader(id, caller);
        Instant prev = before.getScheduledFor();
        WeeklyMeeting m = meetingService.reschedule(
                id,
                req.newScheduledFor() != null ? Instant.parse(req.newScheduledFor()) : null,
                req.newDurationMinutes(),
                caller);
        String note = prev != null && m.getScheduledFor() != null
                ? "rescheduled from " + prev + " to " + m.getScheduledFor()
                : null;
        notifier.dispatchScheduled(m, caller, note);
        return toDto(m);
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public Map<String, Object> complete(@PathVariable UUID id,
                                         @RequestBody CompleteRequest req,
                                         @AuthenticationPrincipal User caller) {
        if (req == null || req.notes() == null || req.notes().trim().length() < 50) {
            throw new com.anvicorp.api.exception.BadRequestException(
                    "notes must be at least 50 chars");
        }
        StringBuilder summary = new StringBuilder();
        if (req.attendance() != null && !req.attendance().isBlank()) {
            summary.append("Attendance: ").append(req.attendance().trim()).append("\n\n");
        }
        summary.append("Notes:\n").append(req.notes().trim());
        if (req.actionItems() != null && !req.actionItems().isBlank()) {
            summary.append("\n\nAction items:\n").append(req.actionItems().trim());
        }
        if (req.blockers() != null && !req.blockers().isBlank()) {
            summary.append("\n\nBlockers:\n").append(req.blockers().trim());
        }
        WeeklyMeeting m = meetingService.complete(id, summary.toString(), caller);
        notifier.dispatchCompleted(m, caller);
        return toDto(m);
    }

    /**
     * Weekly-sessions tracker pill — one-click mark-done. Accepts an
     * optional short note (synthesizes a default when blank) so the
     * trainer doesn't have to type a 50-char incident report from the
     * tracker. The full {@code /complete} endpoint above is still the
     * canonical path for detailed post-meeting summaries.
     */
    public record QuickCompleteRequest(String notes) {}

    @PostMapping("/{id}/quick-complete")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public Map<String, Object> quickComplete(@PathVariable UUID id,
                                              @RequestBody(required = false) QuickCompleteRequest req,
                                              @AuthenticationPrincipal User caller) {
        WeeklyMeeting m = meetingService.completeQuick(
                id, req != null ? req.notes() : null, caller);
        notifier.dispatchCompleted(m, caller);
        return toDto(m);
    }

    @PostMapping("/{id}/mark-missed")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public Map<String, Object> markMissed(@PathVariable UUID id,
                                           @RequestBody MarkMissedRequest req,
                                           @AuthenticationPrincipal User caller) {
        if (req == null || req.reason() == null || req.reason().trim().length() < 20) {
            throw new com.anvicorp.api.exception.BadRequestException(
                    "reason must be at least 20 chars");
        }
        WeeklyMeeting m = meetingService.markMissed(id, req.missedBy(),
                req.reason(), caller);
        notifier.dispatchMissed(m, caller, req.reason());
        return toDto(m);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public Map<String, Object> cancel(@PathVariable UUID id,
                                       @RequestBody(required = false) CancelRequest req,
                                       @AuthenticationPrincipal User caller) {
        WeeklyMeeting m = meetingService.cancel(id, caller);
        notifier.dispatchCancelled(m, caller, req != null ? req.reason() : null);
        return toDto(m);
    }

    /**
     * Regenerate the Zoom meeting (delete + re-create under the current
     * host). Used to recover from a silent Zoom PATCH failure on
     * reschedule (zoom_update_failed=true) or from a null
     * zoom_meeting_id left by a failed initial schedule. Mirrors the ERM
     * interview /zoom/regenerate path.
     */
    @PostMapping("/{id}/zoom/regenerate")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public Map<String, Object> regenerateZoom(@PathVariable UUID id,
                                               @AuthenticationPrincipal User caller) {
        return toDto(meetingService.regenerateZoom(id, caller));
    }

    /** Trainer-facing DTO — includes trainer_notes + zoom_start_url since
     *  this surface is gated to TRAINER / SUPER_ADMIN. The intern-facing
     *  endpoint (existing) strips those fields. */
    static Map<String, Object> toDto(WeeklyMeeting m) {
        if (m == null) return null;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", m.getId());
        r.put("internLifecycleId", m.getInternLifecycleId());
        r.put("scheduledFor", m.getScheduledFor());
        r.put("durationMinutes", m.getDurationMinutes());
        r.put("timezone", m.getTimezone());
        r.put("topic", m.getTopic());
        r.put("agenda", m.getAgenda());
        r.put("zoomMeetingId", m.getZoomMeetingId());
        r.put("zoomJoinUrl", m.getZoomJoinUrl());
        r.put("zoomStartUrl", m.getZoomStartUrl());
        r.put("zoomPassword", m.getZoomPassword());
        r.put("hostUserId", m.getHostUserId());
        r.put("status", m.getStatus());
        r.put("recurrence", m.getRecurrence());
        r.put("recurrenceParentId", m.getRecurrenceParentId());
        r.put("trainerNotes", m.getTrainerNotes());
        r.put("zoomUpdateFailed", Boolean.TRUE.equals(m.getZoomUpdateFailed()));
        r.put("zoomLastError", m.getZoomLastError());
        r.put("createdAt", m.getCreatedAt());
        r.put("updatedAt", m.getUpdatedAt());
        return r;
    }
}
