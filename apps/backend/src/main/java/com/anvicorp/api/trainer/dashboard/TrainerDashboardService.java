package com.anvicorp.api.trainer.dashboard;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.trainer.dashboard.TrainerDashboardResponse.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trainer Phase 1 — Home dashboard service.
 *
 * <p>Single call assembles the 6 doc §4 KPI cards + Today's meetings +
 * Recent activity + unread notification count. Cached at the service
 * layer for {@link TrainerThresholds#DASHBOARD_CACHE_TTL_SECONDS}
 * seconds keyed by caller id; {@code POST /dashboard/refresh}
 * invalidates.</p>
 *
 * <p>Scope is always {@code intern_lifecycles.trainer_id = caller}
 * (SUPER_ADMIN sees all). The 6 KPI queries + 3 list queries (today's
 * meetings, recent activity, unread notifications) total ≤ 10 indexed
 * round trips per cache miss.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrainerDashboardService {

    private static final ZoneId ZONE = ZoneId.of(TrainerThresholds.DEFAULT_TIMEZONE);
    private static final int TODAY_MEETING_LIMIT = 10;
    private static final int RECENT_ACTIVITY_LIMIT = 10;

    private final JdbcTemplate jdbc;

    private record CacheEntry(TrainerDashboardResponse value, Instant cachedAt) {}
    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public TrainerDashboardResponse getDashboard(User caller) {
        requireTrainer(caller);
        UUID callerId = caller.getId();
        CacheEntry cached = cache.get(callerId);
        if (cached != null
                && Duration.between(cached.cachedAt(), Instant.now()).getSeconds()
                        < TrainerThresholds.DASHBOARD_CACHE_TTL_SECONDS) {
            return cached.value();
        }
        TrainerDashboardResponse fresh = build(caller);
        cache.put(callerId, new CacheEntry(fresh, Instant.now()));
        return fresh;
    }

    public void invalidate(User caller) {
        if (caller != null) cache.remove(caller.getId());
    }

    private TrainerDashboardResponse build(User caller) {
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(ZONE);
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        LocalDate weekEnd = weekStart.plusDays(6);

        Map<TrainerKpiKey, KpiSnapshot> kpis = new EnumMap<>(TrainerKpiKey.class);
        kpis.put(TrainerKpiKey.ACTIVE_INTERNS, kpiActiveInterns(caller));
        kpis.put(TrainerKpiKey.PROJECTS_DUE_THIS_WEEK,
                kpiProjectsDueThisWeek(caller, weekStart, weekEnd));
        kpis.put(TrainerKpiKey.SUBMISSIONS_PENDING_REVIEW,
                kpiSubmissionsPendingReview(caller, now));
        kpis.put(TrainerKpiKey.MEETINGS_DUE, kpiMeetingsDue(caller, today));
        kpis.put(TrainerKpiKey.OVERDUE_FEEDBACK, kpiOverdueFeedback(caller, now));
        kpis.put(TrainerKpiKey.REVISION_REQUESTS, kpiRevisionRequests(caller));

        List<TodayMeetingRow> todayMeetings = loadTodayMeetings(caller, today);
        List<RecentActivityRow> recent = loadRecentActivity(caller);
        long unread = loadUnread(caller);
        List<FocusItem> focusItems = buildFocusItems(caller, kpis, weekStart, weekEnd);

        return new TrainerDashboardResponse(
                new Caller(firstName(caller), lastName(caller), primaryRole(caller)),
                now,
                kpis,
                focusItems,
                todayMeetings,
                recent,
                unread);
    }

    // ── "This week" focus strip ──────────────────────────────────────────

    /**
     * Compose the trainer's actionable workload for the current week into
     * one ordered list. Two of the five counts (projects-to-assign and
     * reviews-pending) already live in the KPI map — reused so the strip
     * and the KPI cards can never disagree. Three new lightweight
     * indexed counts cover sessions this week, KTs pending this month,
     * and open doubts.
     */
    private List<FocusItem> buildFocusItems(User caller,
                                            Map<TrainerKpiKey, KpiSnapshot> kpis,
                                            LocalDate weekStart, LocalDate weekEnd) {
        long sessionsThisWeek = countSessionsToActionThisWeek(caller, weekStart, weekEnd);
        long ktPending = countKtPendingThisMonth(caller);
        long projectsToAssign = kpis.get(TrainerKpiKey.ACTIVE_INTERNS) != null
                ? kpis.get(TrainerKpiKey.ACTIVE_INTERNS).urgentCount() : 0L;
        long reviewsPending = kpis.get(TrainerKpiKey.SUBMISSIONS_PENDING_REVIEW) != null
                ? kpis.get(TrainerKpiKey.SUBMISSIONS_PENDING_REVIEW).count() : 0L;
        long doubtsWaiting = countOpenDoubtsForTrainer(caller);

        List<FocusItem> out = new ArrayList<>(5);
        out.add(new FocusItem("SESSIONS_THIS_WEEK",
                "Sessions to schedule/conduct this week",
                sessionsThisWeek,
                "/careers/trainer/weekly-tracker"));
        out.add(new FocusItem("KT_PENDING",
                "KTs pending",
                ktPending,
                "/careers/trainer/active-interns"));
        out.add(new FocusItem("PROJECTS_TO_ASSIGN",
                "Projects to assign",
                projectsToAssign,
                "/careers/trainer/active-interns"));
        out.add(new FocusItem("REVIEWS_PENDING",
                "Project reviews pending",
                reviewsPending,
                "/careers/trainer/pending-reviews"));
        out.add(new FocusItem("DOUBTS_WAITING",
                "Doubts waiting",
                doubtsWaiting,
                "/careers/trainer/doubts"));
        return out;
    }

    /** Active interns who still need either a scheduled or conducted
     *  weekly session for THIS WEEK. "Done" = a meeting whose
     *  scheduled_for date lands inside [weekStart, weekEnd] with
     *  status='COMPLETED'. Everyone else is in the trainer's queue. */
    private long countSessionsToActionThisWeek(User caller,
                                               LocalDate weekStart, LocalDate weekEnd) {
        return countOrZero(
                "SELECT COUNT(*) FROM intern_lifecycles il "
                        + " WHERE il.active_status = 'ACTIVE' "
                        + scopeClause(caller)
                        + " AND NOT EXISTS ( "
                        + "      SELECT 1 FROM weekly_meetings wm "
                        + "       WHERE wm.intern_lifecycle_id = il.id "
                        + "         AND wm.status = 'COMPLETED' "
                        + "         AND wm.scheduled_for::date BETWEEN ? AND ? "
                        + "    ) ",
                appendArgs(scopeArgs(caller),
                        java.sql.Date.valueOf(weekStart),
                        java.sql.Date.valueOf(weekEnd)));
    }

    /** Projects for the current month whose KT step isn't done yet.
     *  Per-project (not per-intern) so a trainer with two slots open on
     *  one intern sees two pending KTs — matches what the active-interns
     *  row chips render. */
    private long countKtPendingThisMonth(User caller) {
        String currentMonth = java.time.YearMonth.now(ZONE).toString();
        return countOrZero(
                "SELECT COUNT(*) FROM projects p "
                        + "  JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                        + " WHERE il.active_status = 'ACTIVE' "
                        + "   AND p.month_year = ? "
                        + "   AND p.status <> 'CANCELLED' "
                        + "   AND (p.kt_status IS NULL OR p.kt_status <> 'DONE') "
                        + scopeClause(caller),
                prependArg(scopeArgs(caller), currentMonth));
    }

    /** Open doubts addressed to this trainer — matches the openOnly
     *  filter on /api/v1/trainer/doubts so the strip count equals the
     *  doubts page count. SUPER_ADMIN sees all (no trainer filter). */
    private long countOpenDoubtsForTrainer(User caller) {
        if (caller.getRoles().contains(UserRole.SUPER_ADMIN)) {
            return countOrZero(
                    "SELECT COUNT(*) FROM doubt_requests "
                            + " WHERE status IN ('PENDING','REPLIED','SESSION_SCHEDULED')");
        }
        return countOrZero(
                "SELECT COUNT(*) FROM doubt_requests "
                        + " WHERE trainer_user_id = ? "
                        + "   AND status IN ('PENDING','REPLIED','SESSION_SCHEDULED')",
                caller.getId());
    }

    // ── KPI computations ─────────────────────────────────────────────────

    private KpiSnapshot kpiActiveInterns(User caller) {
        long total = countOrZero(
                "SELECT COUNT(*) FROM intern_lifecycles il "
                        + " WHERE il.active_status = 'ACTIVE' "
                        + scopeClause(caller),
                scopeArgs(caller));
        // Urgent = active interns with no Project row for the current
        // month_year (Phase 0 partial UNIQUE means at most 2 slots).
        String currentMonth = java.time.YearMonth.now(ZONE).toString();
        long urgent = countOrZero(
                "SELECT COUNT(*) FROM intern_lifecycles il "
                        + " WHERE il.active_status = 'ACTIVE' "
                        + scopeClause(caller)
                        + " AND NOT EXISTS (SELECT 1 FROM projects p "
                        + "                    WHERE p.intern_lifecycle_id = il.id "
                        + "                      AND p.month_year = ? "
                        + "                      AND p.status <> 'CANCELLED') ",
                appendArg(scopeArgs(caller), currentMonth));
        return new KpiSnapshot(
                TrainerKpiKey.ACTIVE_INTERNS,
                "Active interns",
                total, urgent,
                urgent > 0 ? urgent + " without a project this month" : null,
                "/careers/trainer/active-interns");
    }

    private KpiSnapshot kpiProjectsDueThisWeek(User caller, LocalDate from, LocalDate to) {
        Object[] args = appendArgs(scopeArgs(caller), from, to);
        long total = countOrZero(
                "SELECT COUNT(*) FROM projects p "
                        + "  JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                        + " WHERE p.status IN ('NOT_STARTED','IN_PROGRESS','RETURNED') "
                        + "   AND p.due_date BETWEEN ? AND ? "
                        + scopeClause(caller),
                shuffleForOrder(args, /*scopeFirst*/ false));
        long urgent = countOrZero(
                "SELECT COUNT(*) FROM projects p "
                        + "  JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                        + " WHERE p.status IN ('NOT_STARTED','IN_PROGRESS','RETURNED') "
                        + "   AND p.due_date <= CURRENT_DATE + INTERVAL '1 day' "
                        + "   AND p.due_date >= CURRENT_DATE "
                        + scopeClause(caller),
                scopeArgs(caller));
        return new KpiSnapshot(
                TrainerKpiKey.PROJECTS_DUE_THIS_WEEK,
                "Projects due this week",
                total, urgent,
                urgent > 0 ? urgent + " due in next 24h" : null,
                "/careers/trainer/active-interns?filter=due-this-week");
    }

    private KpiSnapshot kpiSubmissionsPendingReview(User caller, Instant now) {
        long total = countOrZero(
                "SELECT COUNT(*) FROM projects p "
                        + "  JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                        + " WHERE p.status = 'SUBMITTED' "
                        + "   AND p.reviewed_at IS NULL "
                        + scopeClause(caller),
                scopeArgs(caller));
        long urgent = countOrZero(
                "SELECT COUNT(*) FROM projects p "
                        + "  JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                        + " WHERE p.status = 'SUBMITTED' "
                        + "   AND p.reviewed_at IS NULL "
                        + "   AND p.submitted_at < NOW() - INTERVAL '"
                        + TrainerThresholds.SUBMISSION_PENDING_URGENT_HOURS + " hours' "
                        + scopeClause(caller),
                scopeArgs(caller));
        return new KpiSnapshot(
                TrainerKpiKey.SUBMISSIONS_PENDING_REVIEW,
                "Submissions pending review",
                total, urgent,
                urgent > 0 ? urgent + " waiting > "
                        + TrainerThresholds.SUBMISSION_PENDING_URGENT_HOURS + "h"
                        : null,
                "/careers/trainer/pending-reviews");
    }

    private KpiSnapshot kpiMeetingsDue(User caller, LocalDate today) {
        long total = countOrZero(
                "SELECT COUNT(*) FROM weekly_meetings wm "
                        + "  JOIN intern_lifecycles il ON il.id = wm.intern_lifecycle_id "
                        + " WHERE wm.status = 'SCHEDULED' "
                        + "   AND wm.scheduled_for BETWEEN NOW() "
                        + "                            AND NOW() + INTERVAL '7 days' "
                        + scopeClause(caller),
                scopeArgs(caller));
        long urgent = countOrZero(
                "SELECT COUNT(*) FROM weekly_meetings wm "
                        + "  JOIN intern_lifecycles il ON il.id = wm.intern_lifecycle_id "
                        + " WHERE wm.status = 'SCHEDULED' "
                        + "   AND wm.scheduled_for::date = CURRENT_DATE "
                        + scopeClause(caller),
                scopeArgs(caller));
        return new KpiSnapshot(
                TrainerKpiKey.MEETINGS_DUE,
                "Meetings due",
                total, urgent,
                urgent > 0 ? urgent + " today" : null,
                "/careers/trainer/weekly-meetings");
    }

    private KpiSnapshot kpiOverdueFeedback(User caller, Instant now) {
        long total = countOrZero(
                "SELECT COUNT(*) FROM projects p "
                        + "  JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                        + " WHERE p.status = 'SUBMITTED' "
                        + "   AND p.reviewed_at IS NULL "
                        + "   AND p.submitted_at < NOW() - INTERVAL '48 hours' "
                        + scopeClause(caller),
                scopeArgs(caller));
        long urgent = countOrZero(
                "SELECT COUNT(*) FROM projects p "
                        + "  JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                        + " WHERE p.status = 'SUBMITTED' "
                        + "   AND p.reviewed_at IS NULL "
                        + "   AND p.submitted_at < NOW() - INTERVAL '"
                        + TrainerThresholds.FEEDBACK_OVERDUE_URGENT_HOURS + " hours' "
                        + scopeClause(caller),
                scopeArgs(caller));
        return new KpiSnapshot(
                TrainerKpiKey.OVERDUE_FEEDBACK,
                "Overdue feedback",
                total, urgent,
                urgent > 0 ? urgent + " > "
                        + TrainerThresholds.FEEDBACK_OVERDUE_URGENT_HOURS + "h"
                        : null,
                "/careers/trainer/pending-reviews?filter=overdue");
    }

    private KpiSnapshot kpiRevisionRequests(User caller) {
        long total = countOrZero(
                "SELECT COUNT(*) FROM projects p "
                        + "  JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                        + " WHERE p.status = 'RETURNED' "
                        + scopeClause(caller),
                scopeArgs(caller));
        long urgent = countOrZero(
                "SELECT COUNT(*) FROM projects p "
                        + "  JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                        + " WHERE p.status = 'RETURNED' "
                        + "   AND p.due_date < CURRENT_DATE "
                        + scopeClause(caller),
                scopeArgs(caller));
        return new KpiSnapshot(
                TrainerKpiKey.REVISION_REQUESTS,
                "Revision requests",
                total, urgent,
                urgent > 0 ? urgent + " past due date" : null,
                "/careers/trainer/active-interns?filter=revision");
    }

    // ── Today's meetings + recent activity + unread ──────────────────────

    private List<TodayMeetingRow> loadTodayMeetings(User caller, LocalDate today) {
        List<TodayMeetingRow> out = new ArrayList<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT wm.id, wm.intern_lifecycle_id, u.full_name, "
                            + "       wm.scheduled_for, wm.duration_minutes, wm.topic, "
                            + "       wm.zoom_start_url, wm.zoom_join_url, wm.host_user_id "
                            + "  FROM weekly_meetings wm "
                            + "  JOIN intern_lifecycles il ON il.id = wm.intern_lifecycle_id "
                            + "  JOIN users u ON u.id = il.user_id "
                            + " WHERE wm.status = 'SCHEDULED' "
                            + "   AND wm.scheduled_for::date = ? "
                            + scopeClause(caller)
                            + " ORDER BY wm.scheduled_for ASC LIMIT " + TODAY_MEETING_LIMIT,
                    prependArg(scopeArgs(caller), java.sql.Date.valueOf(today)))) {
                boolean isHost = caller.getId().equals(
                        nullableUuid(String.valueOf(r.get("host_user_id"))));
                out.add(new TodayMeetingRow(
                        nullableUuid(String.valueOf(r.get("id"))),
                        nullableUuid(String.valueOf(r.get("intern_lifecycle_id"))),
                        (String) r.get("full_name"),
                        instantOf((java.sql.Timestamp) r.get("scheduled_for")),
                        r.get("duration_minutes") != null
                                ? ((Number) r.get("duration_minutes")).intValue() : null,
                        (String) r.get("topic"),
                        isHost ? (String) r.get("zoom_start_url") : null,
                        (String) r.get("zoom_join_url")));
            }
        } catch (Exception e) {
            log.debug("[TrainerDashboard] today meetings failed: {}", e.getMessage());
        }
        return out;
    }

    private List<RecentActivityRow> loadRecentActivity(User caller) {
        List<RecentActivityRow> out = new ArrayList<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT al.timestamp, al.entity_type, al.entity_id, al.action, "
                            + "       al.user_id AS actor_id, u.full_name AS actor_name, "
                            + "       al.subject_user_id, su.full_name AS subject_name "
                            + "  FROM audit_logs al "
                            + "  LEFT JOIN users u ON u.id = al.user_id "
                            + "  LEFT JOIN users su ON su.id = al.subject_user_id "
                            + " WHERE al.subject_user_id IN ("
                            + "         SELECT user_id FROM intern_lifecycles "
                            + "          WHERE active_status = 'ACTIVE' "
                            + (caller.getRoles().contains(UserRole.SUPER_ADMIN)
                                    ? "" : " AND trainer_id = ?")
                            + "       ) "
                            + "   AND al.entity_type IN ('Project','ProjectSubmission',"
                            + "                          'WeeklyMeeting','InternEvaluation',"
                            + "                          'Timesheet','ProjectAssignmentEventLog') "
                            + " ORDER BY al.timestamp DESC LIMIT " + RECENT_ACTIVITY_LIMIT,
                    caller.getRoles().contains(UserRole.SUPER_ADMIN)
                            ? new Object[]{} : new Object[]{caller.getId()})) {
                String entityType = (String) r.get("entity_type");
                UUID entityId = nullableUuid(String.valueOf(r.get("entity_id")));
                out.add(new RecentActivityRow(
                        instantOf((java.sql.Timestamp) r.get("timestamp")),
                        entityType, entityId,
                        (String) r.get("action"),
                        nullableUuid(String.valueOf(r.get("actor_id"))),
                        (String) r.get("actor_name"),
                        nullableUuid(String.valueOf(r.get("subject_user_id"))),
                        (String) r.get("subject_name"),
                        deepLinkFor(entityType, entityId)));
            }
        } catch (Exception e) {
            log.debug("[TrainerDashboard] recent activity failed: {}", e.getMessage());
        }
        return out;
    }

    private long loadUnread(User caller) {
        return countOrZero(
                "SELECT COUNT(*) FROM user_notifications "
                        + " WHERE recipient_user_id = ? AND read_at IS NULL",
                caller.getId());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void requireTrainer(User caller) {
        if (caller == null) throw new ForbiddenException("Caller required");
        if (!caller.getRoles().contains(UserRole.TRAINER)
                && !caller.getRoles().contains(UserRole.SUPER_ADMIN)) {
            throw new ForbiddenException("TRAINER or SUPER_ADMIN required");
        }
    }

    private String scopeClause(User caller) {
        return caller.getRoles().contains(UserRole.SUPER_ADMIN)
                ? "" : " AND il.trainer_id = ? ";
    }

    private Object[] scopeArgs(User caller) {
        return caller.getRoles().contains(UserRole.SUPER_ADMIN)
                ? new Object[]{} : new Object[]{caller.getId()};
    }

    private static Object[] prependArg(Object[] base, Object first) {
        Object[] out = new Object[base.length + 1];
        out[0] = first;
        System.arraycopy(base, 0, out, 1, base.length);
        return out;
    }

    private static Object[] appendArg(Object[] base, Object tail) {
        Object[] out = new Object[base.length + 1];
        System.arraycopy(base, 0, out, 0, base.length);
        out[base.length] = tail;
        return out;
    }

    private static Object[] appendArgs(Object[] base, Object... tail) {
        Object[] out = new Object[base.length + tail.length];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(tail, 0, out, base.length, tail.length);
        return out;
    }

    /** Re-orders args so the BETWEEN ?,? params come before the scope ?
     *  in the projects-due-this-week query (params evaluated in SQL order). */
    private static Object[] shuffleForOrder(Object[] args, boolean scopeFirst) {
        if (args.length < 2) return args;
        // scope arg (if any) is first in `scopeArgs`; the appended dates
        // need to come second in the SQL. Java's PreparedStatement binds
        // by position, so the order we pass MUST match the SQL '?'
        // sequence: BETWEEN ?, ?  THEN scope's ?
        // Here `args` is [scope?, from, to]. We need [from, to, scope?].
        if (args.length == 3) {
            return new Object[]{args[1], args[2], args[0]};
        }
        if (args.length == 2) {
            return new Object[]{args[0], args[1]};
        }
        return args;
    }

    private long countOrZero(String sql, Object... params) {
        try {
            Long c = jdbc.queryForObject(sql, Long.class, params);
            return c == null ? 0L : c;
        } catch (Exception e) {
            log.debug("[TrainerDashboard] count fallback: {} for sql={}",
                    e.getMessage(), sql);
            return 0L;
        }
    }

    private static String firstName(User u) {
        if (u == null || u.getFullName() == null) return "";
        String[] parts = u.getFullName().trim().split("\\s+", 2);
        return parts.length > 0 ? parts[0] : "";
    }

    private static String lastName(User u) {
        if (u == null || u.getFullName() == null) return "";
        String[] parts = u.getFullName().trim().split("\\s+", 2);
        return parts.length > 1 ? parts[1] : "";
    }

    private static String primaryRole(User u) {
        if (u == null || u.getRoles() == null) return "";
        return u.getRoles().contains(UserRole.SUPER_ADMIN) ? "SUPER_ADMIN" : "TRAINER";
    }

    private static String deepLinkFor(String entityType, UUID entityId) {
        if (entityType == null || entityId == null) return null;
        return switch (entityType) {
            case "Project", "ProjectSubmission", "ProjectAssignmentEventLog" ->
                    "/careers/trainer/pending-reviews";
            case "WeeklyMeeting" -> "/careers/trainer/weekly-meetings";
            case "InternEvaluation" -> "/careers/trainer/active-interns";
            case "Timesheet" -> "/careers/trainer/active-interns";
            default -> null;
        };
    }

    private static UUID nullableUuid(String s) {
        if (s == null || s.isBlank() || "null".equals(s)) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    private static Instant instantOf(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
