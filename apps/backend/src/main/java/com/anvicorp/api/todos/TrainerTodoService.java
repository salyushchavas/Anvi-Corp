package com.anvicorp.api.todos;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.todos.TodoPanelResponse.TodoBucket;
import com.anvicorp.api.todos.TodoPanelResponse.TodoItem;
import com.anvicorp.api.trainer.dashboard.TrainerThresholds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Trainer to-do aggregator.
 *
 * <p>Each bucket reuses the exact WHERE predicate of the corresponding
 * {@code TrainerDashboardService} KPI (or the doubts / weekly-tracker
 * queries) so the panel and the Home dashboard stay in lock-step.
 * Auto-resolve is the source of truth: flipping the underlying status
 * column inside the write transaction removes the row from the next
 * poll. To-do buckets are pinned to NOW — they never scope to the
 * sticky month, matching the "Live" tag semantics used elsewhere.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrainerTodoService {

    private static final ZoneId ZONE = ZoneId.of(TrainerThresholds.DEFAULT_TIMEZONE);

    private final JdbcTemplate jdbc;
    private final UserTodoDismissalRepository dismissals;

    @Transactional(readOnly = true)
    public TodoPanelResponse build(User caller) {
        if (caller == null) throw new ForbiddenException("Caller required");
        Set<UserRole> roles = caller.getRoles();
        if (roles == null || (!roles.contains(UserRole.TRAINER)
                && !roles.contains(UserRole.SUPER_ADMIN))) {
            throw new ForbiddenException("TRAINER or SUPER_ADMIN required");
        }
        boolean orgWide = roles.contains(UserRole.SUPER_ADMIN);
        UUID callerId = caller.getId();

        // dismissed set + lastSeenAt intentionally unused for the buckets
        // that don't emit topItems — the pop-up gate + "new" flag still
        // work off any bucket that DOES emit items.
        Set<String> dismissed = dismissals.findTodoKeysForUser(callerId);
        Instant lastSeenAt = caller.getTodoPanelLastSeenAt();

        LocalDate today = LocalDate.now(ZONE);
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        LocalDate weekEnd = weekStart.plusDays(6);

        List<TodoBucket> buckets = new ArrayList<>();
        buckets.add(activeInternsWithoutProject(callerId, orgWide));
        buckets.add(submissionsPendingReview(callerId, orgWide, dismissed));
        buckets.add(overdueFeedback(callerId, orgWide));
        buckets.add(projectsDueThisWeek(callerId, orgWide, weekStart, weekEnd));
        buckets.add(meetingsDueThisWeek(callerId, orgWide, weekStart, weekEnd));
        buckets.add(revisionRequests(callerId, orgWide));
        buckets.add(openDoubts(callerId, orgWide));

        long total = buckets.stream().mapToLong(TodoBucket::count).sum();
        boolean hasNew = computeHasNew(buckets, lastSeenAt);
        return new TodoPanelResponse("TRAINER", buckets, total, hasNew, lastSeenAt);
    }

    // ── Buckets ────────────────────────────────────────────────────────────

    private TodoBucket activeInternsWithoutProject(UUID callerId, boolean orgWide) {
        // Mirrors TrainerDashboardService.kpiActiveInterns urgent-count.
        String actionUrl = "/careers/trainer/active-interns";
        String currentMonth = YearMonth.now(ZONE).toString();
        String scope = orgWide ? "" : " AND il.trainer_id = ? ";
        Object[] params = orgWide
                ? new Object[]{currentMonth}
                : new Object[]{currentMonth, callerId};
        long count = countOrZero(
                "SELECT COUNT(*) FROM intern_lifecycles il "
                        + " WHERE il.active_status = 'ACTIVE' "
                        + "   AND NOT EXISTS (SELECT 1 FROM projects p "
                        + "                     WHERE p.intern_lifecycle_id = il.id "
                        + "                       AND p.month_year = ? "
                        + "                       AND p.status <> 'CANCELLED') "
                        + scope,
                params);
        return new TodoBucket(
                "TRAINER_ACTIVE_NO_PROJECT",
                "Active interns without project",
                "user-check",
                count,
                actionUrl,
                severity(count, 5, 1),
                List.of());
    }

    private TodoBucket submissionsPendingReview(UUID callerId, boolean orgWide,
                                                Set<String> dismissed) {
        // Mirrors TrainerDashboardService.kpiSubmissionsPendingReview (total).
        String actionUrl = "/careers/trainer/pending-reviews";
        String scope = orgWide ? "" : " AND il.trainer_id = ? ";
        Object[] countParams = orgWide ? new Object[0] : new Object[]{callerId};
        long count = countOrZero(
                "SELECT COUNT(*) FROM projects p "
                        + "  JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                        + " WHERE p.status = 'SUBMITTED' "
                        + "   AND p.reviewed_at IS NULL "
                        + scope,
                countParams);
        List<TodoItem> items = new ArrayList<>();
        if (count > 0) {
            Object[] listParams = orgWide ? new Object[]{TOP_N}
                    : new Object[]{callerId, TOP_N};
            for (Map<String, Object> r : safeQueryForList(
                    "SELECT p.id AS p_id, p.title, u.full_name, p.submitted_at "
                            + "  FROM projects p "
                            + "  JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                            + "  JOIN users u ON u.id = il.user_id "
                            + " WHERE p.status = 'SUBMITTED' AND p.reviewed_at IS NULL "
                            + (orgWide ? "" : " AND il.trainer_id = ? ")
                            + " ORDER BY p.submitted_at ASC NULLS LAST "
                            + " LIMIT ?", listParams)) {
                UUID pid = uuid(r.get("p_id"));
                String key = "TRAINER_SUBMISSION_REVIEW:" + pid;
                items.add(new TodoItem(
                        key,
                        strOrDash(r.get("full_name")),
                        strOrDash(r.get("title")),
                        actionUrl,
                        instantOf(r.get("submitted_at")),
                        dismissed.contains(key)));
            }
        }
        return new TodoBucket(
                "TRAINER_SUBMISSION_REVIEW",
                "Submissions pending review",
                "file-text",
                count,
                actionUrl,
                severity(count, 10, 3),
                items);
    }

    private TodoBucket overdueFeedback(UUID callerId, boolean orgWide) {
        // Mirrors TrainerDashboardService.kpiOverdueFeedback (>48h total).
        String actionUrl = "/careers/trainer/pending-reviews?filter=overdue";
        String scope = orgWide ? "" : " AND il.trainer_id = ? ";
        Object[] params = orgWide ? new Object[0] : new Object[]{callerId};
        long count = countOrZero(
                "SELECT COUNT(*) FROM projects p "
                        + "  JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                        + " WHERE p.status = 'SUBMITTED' "
                        + "   AND p.reviewed_at IS NULL "
                        + "   AND p.submitted_at < NOW() - INTERVAL '48 hours' "
                        + scope,
                params);
        return new TodoBucket(
                "TRAINER_FEEDBACK_OVERDUE",
                "Overdue feedback (>48h)",
                "alert-triangle",
                count,
                actionUrl,
                count > 0 ? "URGENT" : "INFO",
                List.of());
    }

    private TodoBucket projectsDueThisWeek(UUID callerId, boolean orgWide,
                                           LocalDate weekStart, LocalDate weekEnd) {
        // Mirrors TrainerDashboardService.kpiProjectsDueThisWeek (total).
        String actionUrl = "/careers/trainer/active-interns?filter=due-this-week";
        String base = "SELECT COUNT(*) FROM projects p "
                + "  JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                + " WHERE p.status IN ('NOT_STARTED','IN_PROGRESS','RETURNED') "
                + "   AND p.due_date BETWEEN ? AND ? ";
        Object[] params = orgWide
                ? new Object[]{java.sql.Date.valueOf(weekStart), java.sql.Date.valueOf(weekEnd)}
                : new Object[]{java.sql.Date.valueOf(weekStart),
                               java.sql.Date.valueOf(weekEnd), callerId};
        String sql = orgWide ? base : base + " AND il.trainer_id = ? ";
        long count = countOrZero(sql, params);
        return new TodoBucket(
                "TRAINER_PROJECT_DUE_WEEK",
                "Projects due this week",
                "calendar-plus",
                count,
                actionUrl,
                severity(count, 10, 3),
                List.of());
    }

    private TodoBucket meetingsDueThisWeek(UUID callerId, boolean orgWide,
                                           LocalDate weekStart, LocalDate weekEnd) {
        // Mirrors TrainerDashboardService.kpiMeetingsDue but week-bounded.
        String actionUrl = "/careers/trainer/weekly-meetings";
        String base = "SELECT COUNT(*) FROM weekly_meetings wm "
                + "  JOIN intern_lifecycles il ON il.id = wm.intern_lifecycle_id "
                + " WHERE wm.status = 'SCHEDULED' "
                + "   AND wm.scheduled_for::date BETWEEN ? AND ? ";
        Object[] params = orgWide
                ? new Object[]{java.sql.Date.valueOf(weekStart), java.sql.Date.valueOf(weekEnd)}
                : new Object[]{java.sql.Date.valueOf(weekStart),
                               java.sql.Date.valueOf(weekEnd), callerId};
        String sql = orgWide ? base : base + " AND il.trainer_id = ? ";
        long count = countOrZero(sql, params);
        return new TodoBucket(
                "TRAINER_MEETING_DUE_WEEK",
                "Meetings due this week",
                "calendar-plus",
                count,
                actionUrl,
                severity(count, 10, 3),
                List.of());
    }

    private TodoBucket revisionRequests(UUID callerId, boolean orgWide) {
        // Mirrors TrainerDashboardService.kpiRevisionRequests (total).
        String actionUrl = "/careers/trainer/active-interns?filter=revision";
        String scope = orgWide ? "" : " AND il.trainer_id = ? ";
        Object[] params = orgWide ? new Object[0] : new Object[]{callerId};
        long count = countOrZero(
                "SELECT COUNT(*) FROM projects p "
                        + "  JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                        + " WHERE p.status = 'RETURNED' "
                        + scope,
                params);
        return new TodoBucket(
                "TRAINER_REVISION_REQUEST",
                "Revision requests",
                "check-square",
                count,
                actionUrl,
                severity(count, 5, 1),
                List.of());
    }

    private TodoBucket openDoubts(UUID callerId, boolean orgWide) {
        // Mirrors TrainerDashboardService.countOpenDoubtsForTrainer.
        String actionUrl = "/careers/trainer/doubts";
        long count;
        if (orgWide) {
            count = countOrZero(
                    "SELECT COUNT(*) FROM doubt_requests "
                            + " WHERE status IN ('PENDING','REPLIED','SESSION_SCHEDULED')");
        } else {
            count = countOrZero(
                    "SELECT COUNT(*) FROM doubt_requests "
                            + " WHERE trainer_user_id = ? "
                            + "   AND status IN ('PENDING','REPLIED','SESSION_SCHEDULED')",
                    callerId);
        }
        return new TodoBucket(
                "TRAINER_OPEN_DOUBT",
                "Open doubts",
                "clock",
                count,
                actionUrl,
                severity(count, 10, 3),
                List.of());
    }

    // ── Helpers (parallel to Erm/Evaluator/ManagerTodoService) ─────────────

    private static final int TOP_N = 3;

    private boolean computeHasNew(List<TodoBucket> buckets, Instant lastSeenAt) {
        boolean anyPending = buckets.stream().anyMatch(b -> b.count() > 0);
        if (!anyPending) return false;
        if (lastSeenAt == null) return true;
        for (TodoBucket b : buckets) {
            for (TodoItem it : b.topItems()) {
                if (it.firstAppearedAt() != null
                        && it.firstAppearedAt().isAfter(lastSeenAt)) {
                    return true;
                }
            }
        }
        return false;
    }

    private long countOrZero(String sql, Object... params) {
        try {
            Long v = jdbc.queryForObject(sql, Long.class, params);
            return v == null ? 0L : v;
        } catch (Exception e) {
            log.debug("[TrainerTodo] count fallback ({}): {}", sql, e.getMessage());
            return 0L;
        }
    }

    private List<Map<String, Object>> safeQueryForList(String sql, Object... params) {
        try {
            return jdbc.queryForList(sql, params);
        } catch (Exception e) {
            log.debug("[TrainerTodo] queryForList fallback ({}): {}", sql, e.getMessage());
            return List.of();
        }
    }

    private static String severity(long count, long urgentThreshold, long warnThreshold) {
        if (count >= urgentThreshold) return "URGENT";
        if (count >= warnThreshold) return "WARN";
        return "INFO";
    }

    private static UUID uuid(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        try { return UUID.fromString(o.toString()); } catch (Exception e) { return null; }
    }

    private static String strOrDash(Object o) {
        return o == null ? "—" : o.toString();
    }

    private static Instant instantOf(Object o) {
        if (o == null) return null;
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (o instanceof Instant i) return i;
        if (o instanceof java.sql.Date d) return d.toInstant();
        return null;
    }
}
