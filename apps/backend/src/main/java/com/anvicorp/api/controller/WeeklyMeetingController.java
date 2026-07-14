package com.anvicorp.api.controller;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.entity.WeeklyMeeting;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.intern.WeeklyMeetingService;
import com.anvicorp.api.trainer.meetings.TrainerMeetingNotificationDispatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 5 weekly trainer-intern meetings. Reuses {@link com.anvicorp.api.integration.zoom.ZoomService}
 * for the actual meeting create/update/delete; this controller exposes the
 * intern-facing applicant-safe view (no {@code zoomStartUrl}, no
 * {@code trainerNotes}) and the trainer-facing CRUD.
 */
@RestController
@RequestMapping("/api/v1/weekly-meetings")
@RequiredArgsConstructor
public class WeeklyMeetingController {

    private final WeeklyMeetingService meetingService;
    private final TrainerMeetingNotificationDispatcher meetingNotifier;

    // ── Trainer ─────────────────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public Map<String, Object> schedule(@RequestBody Map<String, Object> body,
                                         @AuthenticationPrincipal User actor) {
        UUID lifecycleId = UUID.fromString(requireString(body, "internLifecycleId"));
        Instant scheduledFor = Instant.parse(requireString(body, "scheduledFor"));
        Integer duration = (Integer) body.get("durationMinutes");
        String timezone = (String) body.get("timezone");
        String topic = requireString(body, "topic");
        String agenda = (String) body.get("agenda");
        String recurrence = (String) body.get("recurrence");
        WeeklyMeeting m = meetingService.schedule(lifecycleId, scheduledFor,
                duration, timezone, topic, agenda, recurrence, actor);
        // Parity with TrainerWeeklyMeetingController — fan out the
        // WEEKLY_MEETING_SCHEDULED notification so the intern gets the
        // email + in-app cue. Best-effort; a notify hiccup never blocks
        // the create response (the meeting is already saved).
        try {
            meetingNotifier.dispatchScheduled(m, actor, null);
        } catch (Exception e) {
            // dispatcher logs at warn internally; nothing else to do here.
        }
        return toStaffDto(m);
    }

    @PostMapping("/{id}/reschedule")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public Map<String, Object> reschedule(@PathVariable UUID id,
                                           @RequestBody Map<String, Object> body,
                                           @AuthenticationPrincipal User actor) {
        Instant newScheduledFor = Instant.parse(requireString(body, "scheduledFor"));
        Integer duration = (Integer) body.get("durationMinutes");
        WeeklyMeeting m = meetingService.reschedule(id, newScheduledFor, duration, actor);
        try {
            meetingNotifier.dispatchScheduled(m, actor, "Time updated");
        } catch (Exception e) {
            // dispatcher logs at warn internally; never block the response.
        }
        return toStaffDto(m);
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public Map<String, Object> complete(@PathVariable UUID id,
                                         @RequestBody(required = false) Map<String, String> body,
                                         @AuthenticationPrincipal User actor) {
        String notes = body == null ? null : body.get("trainerNotes");
        return toStaffDto(meetingService.complete(id, notes, actor));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public Map<String, Object> cancel(@PathVariable UUID id,
                                       @AuthenticationPrincipal User actor) {
        return toStaffDto(meetingService.cancel(id, actor));
    }

    /**
     * Regenerate the Zoom meeting attached to this weekly meeting. Mirrors
     * the ERM-interview /zoom/regenerate path. Used after a silent Zoom
     * PATCH failure on reschedule (zoom_update_failed=true) or when the
     * initial schedule's Zoom call failed (zoom_meeting_id IS NULL).
     */
    @PostMapping("/{id}/zoom/regenerate")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public Map<String, Object> regenerateZoom(@PathVariable UUID id,
                                               @AuthenticationPrincipal User actor) {
        return toStaffDto(meetingService.regenerateZoom(id, actor));
    }

    // ── Intern ─────────────────────────────────────────────────────────────

    @GetMapping("/mine")
    @PreAuthorize("hasRole('INTERN')")
    public List<Map<String, Object>> mine(@AuthenticationPrincipal User caller) {
        return meetingService.listForUserCaller(caller).stream()
                .map(WeeklyMeetingController::toInternSafeDto)
                .toList();
    }

    // ── Shared read ─────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('INTERN', 'TRAINER', 'REPORTING_MANAGER', 'MANAGER', 'ERM', 'SUPER_ADMIN')")
    public Map<String, Object> getOne(@PathVariable UUID id,
                                       @AuthenticationPrincipal User caller) {
        WeeklyMeeting m = meetingService.requireForReader(id, caller);
        boolean staff = caller.getRoles().contains(UserRole.TRAINER)
                || caller.getRoles().contains(UserRole.REPORTING_MANAGER)
                || caller.getRoles().contains(UserRole.MANAGER)
                || caller.getRoles().contains(UserRole.ERM)
                || caller.getRoles().contains(UserRole.SUPER_ADMIN);
        return staff ? toStaffDto(m) : toInternSafeDto(m);
    }

    @GetMapping("/by-lifecycle/{internLifecycleId}")
    @PreAuthorize("hasAnyRole('TRAINER', 'REPORTING_MANAGER', 'MANAGER', 'ERM', 'SUPER_ADMIN')")
    public List<Map<String, Object>> byLifecycle(@PathVariable UUID internLifecycleId) {
        return meetingService.listForLifecycle(internLifecycleId).stream()
                .map(WeeklyMeetingController::toStaffDto)
                .toList();
    }

    // ── DTO shapers ────────────────────────────────────────────────────────

    /** Staff-facing — includes host-only Zoom start URL and trainer notes. */
    private static Map<String, Object> toStaffDto(WeeklyMeeting m) {
        Map<String, Object> dto = baseDto(m);
        dto.put("zoomStartUrl", m.getZoomStartUrl());
        dto.put("trainerNotes", m.getTrainerNotes());
        dto.put("zoomLastError", m.getZoomLastError());
        return dto;
    }

    /**
     * Intern-facing — by construction excludes {@code zoomStartUrl} and
     * {@code trainerNotes}. Adding host-only state here is a bug; route
     * through {@link #toStaffDto} instead.
     */
    public static Map<String, Object> toInternSafeDto(WeeklyMeeting m) {
        return baseDto(m);
    }

    private static Map<String, Object> baseDto(WeeklyMeeting m) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", m.getId());
        dto.put("internLifecycleId", m.getInternLifecycleId());
        dto.put("scheduledFor", m.getScheduledFor());
        dto.put("durationMinutes", m.getDurationMinutes());
        dto.put("timezone", m.getTimezone());
        dto.put("topic", m.getTopic());
        dto.put("agenda", m.getAgenda());
        dto.put("zoomMeetingId", m.getZoomMeetingId());
        dto.put("zoomJoinUrl", m.getZoomJoinUrl());
        dto.put("zoomPassword", m.getZoomPassword());
        dto.put("hostUserId", m.getHostUserId());
        dto.put("status", m.getStatus());
        dto.put("recurrence", m.getRecurrence());
        dto.put("recurrenceParentId", m.getRecurrenceParentId());
        dto.put("zoomUpdateFailed", Boolean.TRUE.equals(m.getZoomUpdateFailed()));
        return dto;
    }

    private static String requireString(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new BadRequestException(key + " is required");
        }
        return v.toString();
    }
}
