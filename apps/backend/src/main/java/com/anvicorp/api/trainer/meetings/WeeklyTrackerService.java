package com.anvicorp.api.trainer.meetings;

import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.entity.WeeklyMeeting;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.repository.WeeklyMeetingRepository;
import com.anvicorp.api.service.timesheet.MonthWeeks;
import com.anvicorp.api.trainer.meetings.WeeklyTrackerDtos.InternRow;
import com.anvicorp.api.trainer.meetings.WeeklyTrackerDtos.InternWeekCell;
import com.anvicorp.api.trainer.meetings.WeeklyTrackerDtos.TrackerResponse;
import com.anvicorp.api.trainer.meetings.WeeklyTrackerDtos.WeekSlot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Trainer weekly-sessions tracker. Joins the trainer's active interns
 * with the canonical Monday's-month week list ({@link MonthWeeks}) and
 * decorates each cell with the per-week meeting status (PENDING /
 * SCHEDULED / DONE / MISSED / CANCELLED).
 *
 * <p>Pure read; no state changes. Schedule / mark-done flow through
 * {@link com.anvicorp.api.intern.WeeklyMeetingService} via the
 * existing {@link TrainerWeeklyMeetingController} endpoints.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WeeklyTrackerService {

    private final InternLifecycleRepository lifecycleRepository;
    private final UserRepository userRepository;
    private final WeeklyMeetingRepository meetingRepository;

    @Transactional(readOnly = true)
    public TrackerResponse buildTracker(YearMonth period, UUID singleInternLifecycleId,
                                         User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required");
        if (caller.getRoles() == null
                || (!caller.getRoles().contains(UserRole.TRAINER)
                    && !caller.getRoles().contains(UserRole.SUPER_ADMIN))) {
            throw new ForbiddenException("TRAINER role required");
        }
        if (period == null) period = YearMonth.now(ZoneOffset.UTC);

        // 1) Week list — single source of truth across the platform.
        List<MonthWeeks.WorkWeek> workWeeks = MonthWeeks.workWeeksOf(period);
        List<WeekSlot> slots = workWeeks.stream()
                .map(w -> new WeekSlot(w.weekNumber(), w.weekStart()))
                .toList();
        Map<LocalDate, Integer> weekNumberByMonday = new HashMap<>();
        for (MonthWeeks.WorkWeek w : workWeeks) {
            weekNumberByMonday.put(w.weekStart(), w.weekNumber());
        }

        // 2) Trainer's active interns. Single-trainer-org fallback:
        // when lifecycle.trainerId is null, treat the caller as the
        // de-facto owner (mirrors TrainerScopeGuard). SUPER_ADMIN sees
        // everything.
        boolean isSuperAdmin = caller.getRoles().contains(UserRole.SUPER_ADMIN);
        List<InternLifecycle> roster = lifecycleRepository
                .findByActiveStatusOrderByEmployeeIdAsc("ACTIVE")
                .stream()
                .filter(lc -> {
                    if (singleInternLifecycleId != null
                            && !singleInternLifecycleId.equals(lc.getId())) {
                        return false;
                    }
                    if (isSuperAdmin) return true;
                    return lc.getTrainerId() == null
                            || caller.getId().equals(lc.getTrainerId());
                })
                .toList();
        if (roster.isEmpty()) {
            return new TrackerResponse(period.getYear(), period.getMonthValue(),
                    Instant.now(), slots, List.of());
        }

        // 3) Bulk-fetch every weekly meeting for these interns within
        // the period [first Monday, last Monday + 7d) — narrow scan.
        LocalDate from = workWeeks.get(0).weekStart();
        LocalDate toExclusive = workWeeks.get(workWeeks.size() - 1)
                .weekStart().plusDays(7);
        Instant fromInstant = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInstant = toExclusive.atStartOfDay(ZoneOffset.UTC).toInstant();
        List<UUID> lifecycleIds = roster.stream().map(InternLifecycle::getId).toList();
        List<WeeklyMeeting> meetings = meetingRepository
                .findByInternLifecycleIdInAndScheduledForBetween(
                        lifecycleIds, fromInstant, toInstant);

        // 4) Bucket meetings per (lifecycleId, weekStart Monday).
        // When more than one meeting lands in the same week (recurring +
        // ad-hoc, or a reschedule + new), prefer the most-actionable:
        // SCHEDULED > COMPLETED > NO_SHOW > CANCELLED. The trainer's
        // surface wants to see "what's still on the calendar" before
        // anything else.
        Map<UUID, Map<LocalDate, WeeklyMeeting>> byLifecycleAndWeek = new HashMap<>();
        for (WeeklyMeeting m : meetings) {
            if (m.getScheduledFor() == null) continue;
            LocalDate day = m.getScheduledFor().atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate monday = day.with(DayOfWeek.MONDAY);
            if (!weekNumberByMonday.containsKey(monday)) continue;
            byLifecycleAndWeek
                    .computeIfAbsent(m.getInternLifecycleId(), k -> new HashMap<>())
                    .merge(monday, m, WeeklyTrackerService::preferActionable);
        }

        // 5) Bulk-fetch intern user rows for names.
        List<UUID> userIds = roster.stream()
                .map(InternLifecycle::getUserId)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<UUID, User> users = userRepository.findAllById(userIds).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, u -> u));

        // 6) Build the per-intern rows.
        List<InternRow> rows = new ArrayList<>(roster.size());
        for (InternLifecycle lc : roster) {
            User intern = lc.getUserId() != null ? users.get(lc.getUserId()) : null;
            Map<LocalDate, WeeklyMeeting> perWeek = byLifecycleAndWeek
                    .getOrDefault(lc.getId(), Map.of());
            int done = 0, scheduled = 0, pending = 0, missed = 0;
            List<InternWeekCell> cells = new ArrayList<>(workWeeks.size());
            for (MonthWeeks.WorkWeek w : workWeeks) {
                WeeklyMeeting m = perWeek.get(w.weekStart());
                String uiStatus = uiStatusOf(m);
                switch (uiStatus) {
                    case "DONE" -> done++;
                    case "SCHEDULED" -> scheduled++;
                    case "MISSED" -> missed++;
                    case "PENDING" -> pending++;
                    default -> {}
                }
                cells.add(new InternWeekCell(
                        w.weekNumber(),
                        w.weekStart(),
                        uiStatus,
                        m != null ? m.getId() : null,
                        m != null ? m.getScheduledFor() : null,
                        m != null ? m.getDurationMinutes() : null,
                        m != null ? m.getTimezone() : null,
                        m != null ? m.getTopic() : null,
                        m != null ? m.getZoomJoinUrl() : null,
                        m != null ? m.getZoomStartUrl() : null));
            }
            rows.add(new InternRow(
                    lc.getId(),
                    lc.getUserId(),
                    intern != null ? intern.getFullName() : null,
                    lc.getEmployeeId(),
                    done, scheduled, pending, missed,
                    cells));
        }
        rows.sort(Comparator.comparing(
                InternRow::internName,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));

        return new TrackerResponse(period.getYear(), period.getMonthValue(),
                Instant.now(), slots, rows);
    }

    /** Entity status → UI label. CANCELLED maps to PENDING so the
     *  trainer can immediately re-schedule that slot. */
    private static String uiStatusOf(WeeklyMeeting m) {
        if (m == null) return "PENDING";
        String s = m.getStatus();
        if (s == null) return "PENDING";
        return switch (s) {
            case "SCHEDULED" -> "SCHEDULED";
            case "COMPLETED" -> "DONE";
            case "NO_SHOW"   -> "MISSED";
            case "CANCELLED" -> "PENDING";
            default          -> "PENDING";
        };
    }

    /** Merge function: SCHEDULED > COMPLETED > NO_SHOW > CANCELLED. */
    private static WeeklyMeeting preferActionable(WeeklyMeeting a, WeeklyMeeting b) {
        return priorityOf(a) <= priorityOf(b) ? a : b;
    }

    private static int priorityOf(WeeklyMeeting m) {
        return switch (m.getStatus() == null ? "" : m.getStatus()) {
            case "SCHEDULED" -> 0;
            case "COMPLETED" -> 1;
            case "NO_SHOW"   -> 2;
            case "CANCELLED" -> 3;
            default          -> 4;
        };
    }

    /** Suppress unused-import warning from LinkedHashMap (kept for future use). */
    @SuppressWarnings("unused")
    private static final LinkedHashMap<?, ?> _kept = new LinkedHashMap<>();
}
