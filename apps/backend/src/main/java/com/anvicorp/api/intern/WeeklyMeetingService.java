package com.anvicorp.api.intern;

import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.entity.WeeklyMeeting;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.integration.meeting.MeetingProvider;
import com.anvicorp.api.integration.meeting.MeetingRequest;
import com.anvicorp.api.integration.meeting.MeetingResponse;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.repository.WeeklyMeetingRepository;
import com.anvicorp.api.trainer.TrainerScopeGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 5 weekly trainer-intern meeting orchestration. Reuses the existing
 * {@link ZoomService} for actual meeting create/update/delete. Trainer scope
 * enforced via {@code intern_lifecycles.trainer_id}; SUPER_ADMIN bypasses.
 *
 * <p>Recurrence: when {@code recurrence=WEEKLY} the first occurrence triggers
 * a Zoom create, and the next 11 child rows are persisted upfront sharing
 * the same {@code zoom_meeting_id} so the intern UI can list all 12 weeks
 * without a Zoom round-trip per row.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WeeklyMeetingService {

    private static final int WEEKLY_OCCURRENCES = 12;

    private final WeeklyMeetingRepository meetingRepository;
    private final InternLifecycleRepository lifecycleRepository;
    private final UserRepository userRepository;
    private final MeetingProvider meetingProvider;
    private final TrainerScopeGuard trainerScopeGuard;

    // ── Trainer commands ────────────────────────────────────────────────────

    @Transactional
    public WeeklyMeeting schedule(UUID internLifecycleId,
                                   Instant scheduledFor,
                                   Integer durationMinutes,
                                   String timezone,
                                   String topic,
                                   String agenda,
                                   String recurrence,
                                   User actor) {
        if (scheduledFor == null || scheduledFor.isBefore(Instant.now().minus(Duration.ofMinutes(5)))) {
            throw new BadRequestException("scheduledFor must be in the future");
        }
        if (topic == null || topic.isBlank()) {
            throw new BadRequestException("topic is required");
        }
        InternLifecycle lc = lifecycleRepository.findById(internLifecycleId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + internLifecycleId));
        ensureTrainerScope(lc, actor);
        ensureActiveIntern(lc);

        int duration = durationMinutes == null ? 30 : Math.max(15, Math.min(180, durationMinutes));
        String tz = (timezone == null || timezone.isBlank()) ? "UTC" : timezone;

        // Create the Zoom meeting (best-effort).
        String zoomId = null;
        String joinUrl = null;
        String startUrl = null;
        String password = null;
        if (meetingProvider.isReady()) {
            try {
                String hostId = actor.getZoomEmail() != null && !actor.getZoomEmail().isBlank()
                        ? actor.getZoomEmail() : "me";
                MeetingResponse z = meetingProvider.createMeeting(
                        new MeetingRequest(hostId, topic, scheduledFor, duration, tz, agenda));
                zoomId = z.providerMeetingId();
                joinUrl = z.joinUrl();
                startUrl = z.startUrl();
                password = z.password();
                log.info("[WeeklyMeeting] {} meeting created id={} for lifecycle={}",
                        meetingProvider.providerName(), zoomId, lc.getId());
            } catch (Exception e) {
                log.warn("[WeeklyMeeting] {} create failed (non-fatal): {}",
                        meetingProvider.providerName(), e.getMessage());
            }
        }

        WeeklyMeeting first = WeeklyMeeting.builder()
                .internLifecycleId(lc.getId())
                .scheduledFor(scheduledFor)
                .durationMinutes(duration)
                .timezone(tz)
                .topic(topic)
                .agenda(agenda)
                .zoomMeetingId(zoomId)
                .zoomJoinUrl(joinUrl)
                .zoomStartUrl(startUrl)
                .zoomPassword(password)
                .hostUserId(actor.getId())
                .status("SCHEDULED")
                .recurrence("WEEKLY".equalsIgnoreCase(recurrence) ? "WEEKLY" : null)
                .build();
        first = meetingRepository.save(first);

        // Project child occurrences for a recurring series so the intern's UI
        // can show 12 weeks without per-week Zoom calls. Children share the
        // same Zoom meeting id (recurring meeting on the Zoom side).
        if ("WEEKLY".equalsIgnoreCase(recurrence)) {
            for (int i = 1; i < WEEKLY_OCCURRENCES; i++) {
                WeeklyMeeting child = WeeklyMeeting.builder()
                        .internLifecycleId(lc.getId())
                        .scheduledFor(scheduledFor.plus(Duration.ofDays(7L * i)))
                        .durationMinutes(duration)
                        .timezone(tz)
                        .topic(topic)
                        .agenda(agenda)
                        .zoomMeetingId(zoomId)
                        .zoomJoinUrl(joinUrl)
                        .zoomStartUrl(startUrl)
                        .zoomPassword(password)
                        .hostUserId(actor.getId())
                        .status("SCHEDULED")
                        .recurrence(null)
                        .recurrenceParentId(first.getId())
                        .build();
                meetingRepository.save(child);
            }
        }
        return first;
    }

    @Transactional
    public WeeklyMeeting reschedule(UUID meetingId,
                                     Instant newScheduledFor,
                                     Integer newDurationMinutes,
                                     User actor) {
        WeeklyMeeting m = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Meeting not found: " + meetingId));
        ensureHostOrSuperAdmin(m, actor);
        if (!"SCHEDULED".equals(m.getStatus())) {
            throw new ConflictException(
                    "Only SCHEDULED meetings can be rescheduled (current: " + m.getStatus() + ")");
        }
        if (newScheduledFor == null || newScheduledFor.isBefore(Instant.now())) {
            throw new BadRequestException("scheduledFor must be in the future");
        }
        // Recurring-series cross-contamination guard. All 12 children share
        // one zoom_meeting_id; PATCHing that meeting from a single
        // occurrence shifts the entire series on Zoom's side without moving
        // the other 11 local rows, desyncing the series. Block reschedule
        // for both the parent (recurrence=WEEKLY) and any child
        // (recurrenceParentId != null). Trainer must cancel + re-create the
        // series to change the schedule.
        boolean isSeriesParent = "WEEKLY".equalsIgnoreCase(m.getRecurrence());
        boolean isSeriesChild = m.getRecurrenceParentId() != null;
        if (isSeriesParent || isSeriesChild) {
            throw new ConflictException(
                    "Per-occurrence reschedule isn't supported for recurring weekly "
                            + "meetings — all 12 occurrences share one Zoom meeting "
                            + "and would desync. Cancel the series and schedule a new "
                            + "one with the updated time.");
        }
        if (newDurationMinutes != null) {
            m.setDurationMinutes(Math.max(15, Math.min(180, newDurationMinutes)));
        }
        m.setScheduledFor(newScheduledFor);
        // Branch 1 — existing Zoom meeting: PATCH and persist the outcome.
        if (m.getZoomMeetingId() != null && meetingProvider.isReady()) {
            try {
                meetingProvider.updateMeeting(m.getZoomMeetingId(),
                        new MeetingRequest(null, m.getTopic(), m.getScheduledFor(),
                                m.getDurationMinutes(), m.getTimezone(), m.getAgenda()));
                m.setZoomUpdateFailed(false);
                m.setZoomLastError(null);
            } catch (Exception e) {
                // Persist the failure so the UI can render Regenerate even
                // on a later GET — the previous "log.warn + commit anyway"
                // left the trainer with no signal at all.
                log.warn("[WeeklyMeeting] Zoom update failed for {}: {}",
                        m.getId(), e.getMessage());
                m.setZoomUpdateFailed(true);
                m.setZoomLastError(truncate(e.getMessage()));
            }
        } else if (m.getZoomMeetingId() == null && meetingProvider.isReady()) {
            // Branch 2 — null-id fall-through. The original schedule's Zoom
            // create failed; try to attach a fresh meeting now. Mirrors the
            // ERM-interview reschedule pattern.
            attachZoomMeeting(m, actor);
        }
        return meetingRepository.save(m);
    }

    /**
     * Regenerate the Zoom meeting on a SCHEDULED weekly meeting — delete
     * the old one (if any) and create a fresh one under the current host.
     * Used after a reschedule whose PATCH failed (zoom_update_failed=true)
     * or after an initial create failure left zoom_meeting_id null. Mirrors
     * {@code ErmInterviewService.regenerateZoom}.
     *
     * <p>Per-occurrence regenerate of a WEEKLY series is blocked for the
     * same desync reason that blocks per-occurrence reschedule.</p>
     */
    @Transactional
    public WeeklyMeeting regenerateZoom(UUID meetingId, User actor) {
        WeeklyMeeting m = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Meeting not found: " + meetingId));
        ensureHostOrSuperAdmin(m, actor);
        if (!"SCHEDULED".equals(m.getStatus())) {
            throw new ConflictException(
                    "Regenerate only allowed from SCHEDULED (current: " + m.getStatus() + ")");
        }
        boolean isSeriesParent = "WEEKLY".equalsIgnoreCase(m.getRecurrence());
        boolean isSeriesChild = m.getRecurrenceParentId() != null;
        if (isSeriesParent || isSeriesChild) {
            throw new ConflictException(
                    "Per-occurrence Zoom regenerate isn't supported for recurring "
                            + "weekly meetings (would desync the shared Zoom meeting "
                            + "from the other occurrences). Cancel and re-schedule "
                            + "the series instead.");
        }
        if (!meetingProvider.isReady()) {
            String provider = meetingProvider.providerName();
            if (meetingProvider.isForceDisabled()) {
                throw new ConflictException(
                        "Meeting provider '" + provider + "' is force-disabled. "
                                + "Unset the kill-switch to use the configured credentials.");
            }
            throw new ConflictException(
                    "Meeting provider '" + provider + "' is not configured on the server.");
        }
        // Delete first so we don't orphan the old meeting on Zoom.
        if (m.getZoomMeetingId() != null) {
            try {
                meetingProvider.deleteMeeting(m.getZoomMeetingId());
            } catch (Exception e) {
                log.warn("[WeeklyMeeting] Zoom delete failed during regenerate for {}: {}",
                        m.getId(), e.getMessage());
            }
            m.setZoomMeetingId(null);
            m.setZoomJoinUrl(null);
            m.setZoomStartUrl(null);
            m.setZoomPassword(null);
        }
        User host = m.getHostUserId() != null
                ? userRepository.findById(m.getHostUserId()).orElse(actor)
                : actor;
        attachZoomMeeting(m, host);
        if (m.getZoomMeetingId() == null) {
            // attachZoomMeeting failed — surface the persisted error.
            throw new ConflictException("Zoom meeting recreation failed: "
                    + (m.getZoomLastError() != null ? m.getZoomLastError() : "unknown error"));
        }
        return meetingRepository.save(m);
    }

    /**
     * Best-effort attach of a Zoom meeting onto an existing row. Sets
     * zoom_update_failed + zoom_last_error appropriately. Never throws —
     * caller must inspect the row state after.
     */
    private void attachZoomMeeting(WeeklyMeeting m, User host) {
        String hostKey = host != null && host.getZoomEmail() != null
                && !host.getZoomEmail().isBlank()
                ? host.getZoomEmail() : "me";
        try {
            MeetingResponse z = meetingProvider.createMeeting(
                    new MeetingRequest(hostKey, m.getTopic(), m.getScheduledFor(),
                            m.getDurationMinutes(), m.getTimezone(), m.getAgenda()));
            m.setZoomMeetingId(z.providerMeetingId());
            m.setZoomJoinUrl(z.joinUrl());
            m.setZoomStartUrl(z.startUrl());
            m.setZoomPassword(z.password());
            m.setZoomUpdateFailed(false);
            m.setZoomLastError(null);
        } catch (Exception e) {
            log.warn("[WeeklyMeeting] Zoom create failed during attach for {}: {}",
                    m.getId(), e.getMessage());
            m.setZoomUpdateFailed(true);
            m.setZoomLastError(truncate(e.getMessage()));
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

    @Transactional
    public WeeklyMeeting complete(UUID meetingId, String trainerNotes, User actor) {
        WeeklyMeeting m = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Meeting not found: " + meetingId));
        ensureHostOrSuperAdmin(m, actor);
        if ("COMPLETED".equals(m.getStatus())) return m;
        // Trainer Phase 3 — guard against marking a future meeting complete by
        // mistake. 15-minute leeway tolerates clock skew + early-start
        // sessions but blocks "complete a meeting 3 days from now".
        if (m.getScheduledFor() != null
                && m.getScheduledFor().isAfter(Instant.now().plus(Duration.ofMinutes(15)))) {
            throw new ConflictException(
                    "Cannot mark a future meeting (scheduled "
                            + m.getScheduledFor() + ") as completed");
        }
        m.setStatus("COMPLETED");
        if (trainerNotes != null && !trainerNotes.isBlank()) {
            m.setTrainerNotes(trainerNotes.trim());
        }
        return meetingRepository.save(m);
    }

    /**
     * Lightweight mark-done variant for the weekly-sessions tracker
     * pill. Reuses {@link #complete} (same guards, same audit-log
     * shape) but accepts an optional short note — synthesizes a
     * default when blank so the tracker's one-click "Mark done" doesn't
     * require typing a 50-char incident report.
     */
    @Transactional
    public WeeklyMeeting completeQuick(UUID meetingId, String trainerNote, User actor) {
        String note = trainerNote == null || trainerNote.isBlank()
                ? "Marked done from weekly-sessions tracker."
                : trainerNote.trim();
        return complete(meetingId, note, actor);
    }

    /** Trainer Phase 3 — manual mark-missed by the host trainer when the
     *  intern was a no-show. The {@code MissedMeetingDetectorJob} fires the
     *  same path automatically when a SCHEDULED meeting elapses past its
     *  4-hour grace window. */
    @Transactional
    public WeeklyMeeting markMissed(UUID meetingId, String missedBy,
                                     String reason, User actor) {
        WeeklyMeeting m = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Meeting not found: " + meetingId));
        ensureHostOrSuperAdmin(m, actor);
        if (!"SCHEDULED".equals(m.getStatus())) {
            throw new ConflictException(
                    "Only SCHEDULED meetings can be marked missed (current: "
                            + m.getStatus() + ")");
        }
        m.setStatus("NO_SHOW");
        StringBuilder note = new StringBuilder();
        if (m.getTrainerNotes() != null && !m.getTrainerNotes().isBlank()) {
            note.append(m.getTrainerNotes()).append("\n\n");
        }
        note.append("Marked NO_SHOW");
        if (missedBy != null && !missedBy.isBlank()) {
            note.append(" — missed by ").append(missedBy.trim());
        }
        if (reason != null && !reason.isBlank()) {
            note.append(": ").append(reason.trim());
        }
        m.setTrainerNotes(note.toString());
        return meetingRepository.save(m);
    }

    /** Internal helper used by the {@code MissedMeetingDetectorJob} so the
     *  auto-flip path doesn't trip the host-or-super-admin guard. */
    @Transactional
    public WeeklyMeeting markMissedSystem(UUID meetingId, String reason) {
        WeeklyMeeting m = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Meeting not found: " + meetingId));
        if (!"SCHEDULED".equals(m.getStatus())) return m;
        m.setStatus("NO_SHOW");
        String prev = m.getTrainerNotes() != null && !m.getTrainerNotes().isBlank()
                ? m.getTrainerNotes() + "\n\n" : "";
        m.setTrainerNotes(prev + "Auto-detected NO_SHOW: " + reason);
        return meetingRepository.save(m);
    }

    @Transactional
    public WeeklyMeeting cancel(UUID meetingId, User actor) {
        WeeklyMeeting m = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Meeting not found: " + meetingId));
        ensureHostOrSuperAdmin(m, actor);
        if (!"SCHEDULED".equals(m.getStatus())) {
            throw new ConflictException("Only SCHEDULED meetings can be cancelled");
        }
        // Only delete the Zoom meeting when cancelling the FIRST occurrence of
        // a series; child occurrences share the same Zoom id and shouldn't
        // tear down the whole recurring meeting.
        boolean isSeriesParent = "WEEKLY".equals(m.getRecurrence());
        boolean isStandalone = m.getRecurrenceParentId() == null && m.getRecurrence() == null;
        if (m.getZoomMeetingId() != null && (isSeriesParent || isStandalone)
                && meetingProvider.isReady()) {
            try {
                meetingProvider.deleteMeeting(m.getZoomMeetingId());
            } catch (Exception e) {
                log.warn("[WeeklyMeeting] Zoom delete failed (non-fatal) for {}: {}",
                        m.getId(), e.getMessage());
            }
        }
        m.setStatus("CANCELLED");
        return meetingRepository.save(m);
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<WeeklyMeeting> listForLifecycle(UUID internLifecycleId) {
        return meetingRepository.findByInternLifecycleIdOrderByScheduledForAsc(internLifecycleId);
    }

    @Transactional(readOnly = true)
    public List<WeeklyMeeting> listForUserCaller(User caller) {
        // INTERN view: scope by their own InternLifecycle.user_id.
        InternLifecycle lc = lifecycleRepository.findByUserId(caller.getId()).orElse(null);
        if (lc == null) return List.of();
        return meetingRepository.findByInternLifecycleIdOrderByScheduledForAsc(lc.getId());
    }

    @Transactional(readOnly = true)
    public WeeklyMeeting requireForReader(UUID meetingId, User caller) {
        WeeklyMeeting m = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Meeting not found: " + meetingId));
        boolean staff = caller.getRoles().contains(UserRole.TRAINER)
                || caller.getRoles().contains(UserRole.REPORTING_MANAGER)
                || caller.getRoles().contains(UserRole.MANAGER)
                || caller.getRoles().contains(UserRole.ERM)
                || caller.getRoles().contains(UserRole.SUPER_ADMIN);
        if (staff) return m;
        // INTERN: must own the lifecycle.
        InternLifecycle lc = lifecycleRepository.findByUserId(caller.getId()).orElse(null);
        if (lc == null || !lc.getId().equals(m.getInternLifecycleId())) {
            throw new ForbiddenException("Not allowed to view this meeting");
        }
        return m;
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private void ensureTrainerScope(InternLifecycle lc, User actor) {
        // Delegate to the shared TrainerScopeGuard so meeting-schedule
        // inherits the same single-trainer fallback as KT / project
        // assign / project review — null lc.trainerId now allows any
        // TRAINER instead of 403-ing.
        trainerScopeGuard.requireTrainerOwnership(lc, actor);
    }

    private void ensureHostOrSuperAdmin(WeeklyMeeting m, User actor) {
        if (actor.getRoles().contains(UserRole.SUPER_ADMIN)) return;
        if (m.getHostUserId() != null && m.getHostUserId().equals(actor.getId())) return;
        throw new ForbiddenException("Caller is not the host trainer for this meeting");
    }

    private void ensureActiveIntern(InternLifecycle lc) {
        // Trainer Phase 3 — accept both ACTIVE and PROSPECTIVE so demo /
        // pre-start interns can still have prep meetings scheduled.
        String s = lc.getActiveStatus();
        if (!"ACTIVE".equals(s) && !"PROSPECTIVE".equals(s)) {
            throw new ConflictException(
                    "Lifecycle is not schedulable (current: " + s + ")");
        }
    }

    /** Trainer Phase 3 — meetings hosted by the caller. SUPER_ADMIN gets
     *  everything; TRAINER sees just their own. */
    @Transactional(readOnly = true)
    public List<WeeklyMeeting> listForTrainer(User caller) {
        if (caller == null) return List.of();
        if (caller.getRoles().contains(UserRole.SUPER_ADMIN)) {
            return meetingRepository.findAll();
        }
        return meetingRepository.findByHostUserIdOrderByScheduledForDesc(caller.getId());
    }
}
