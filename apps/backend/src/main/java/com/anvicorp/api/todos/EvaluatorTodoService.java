package com.anvicorp.api.todos;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.todos.TodoPanelResponse.TodoBucket;
import com.anvicorp.api.todos.TodoPanelResponse.TodoItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * EVALUATOR to-do aggregator.
 *
 * <p>Every bucket reuses the exact WHERE clause of the corresponding
 * evaluator service so the panel and the queue page stay in lock-step:</p>
 * <ul>
 *   <li>Projects awaiting evaluation (viva queue) — {@code PendingVivasService.list}
 *       ({@code projects.status='PENDING_VIVA'} + evaluator scope with single-
 *       evaluator null fallback).</li>
 *   <li>Sessions to conduct — {@code PendingEvaluationsService.list} scheduled tab
 *       ({@code intern_evaluations.status IN ('SCHEDULED','IN_PROGRESS')}).</li>
 *   <li>Pending acknowledgments — {@code EvaluatorDashboardService.kpiPendingAcknowledgments}
 *       ({@code status='PUBLISHED' AND intern_acknowledged_at IS NULL AND published_at &gt; NOW()-14d}).</li>
 *   <li>Overdue evaluations (this month) — {@code kpiOverdueEvaluations}
 *       (active evaluees with no PUBLISHED evaluation in the current month).</li>
 *   <li>I-983 evaluations in flight — {@code I983EvaluationWorkflowService.list}
 *       ({@code status IN ('SCHEDULED','IN_PROGRESS')}).</li>
 * </ul>
 *
 * <p>Auto-resolve guarantee: identical predicates → identical drop-on-write
 * behaviour. Publishing an evaluation flips {@code status → 'PUBLISHED'} in
 * the same transaction so the bucket empties on the next poll.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvaluatorTodoService {

    private static final int TOP_N = 3;

    private final JdbcTemplate jdbc;
    private final UserTodoDismissalRepository dismissals;

    @Transactional(readOnly = true)
    public TodoPanelResponse build(User caller) {
        if (caller == null) throw new ForbiddenException("Caller required");
        Set<UserRole> roles = caller.getRoles();
        if (roles == null || (!roles.contains(UserRole.EVALUATOR)
                && !roles.contains(UserRole.SUPER_ADMIN))) {
            throw new ForbiddenException("EVALUATOR or SUPER_ADMIN required");
        }
        boolean orgWide = roles.contains(UserRole.SUPER_ADMIN);
        UUID evaluatorId = caller.getId();

        Set<String> dismissed = dismissals.findTodoKeysForUser(caller.getId());
        Instant lastSeenAt = caller.getTodoPanelLastSeenAt();

        List<TodoBucket> buckets = new ArrayList<>();
        buckets.add(pendingVivas(evaluatorId, orgWide, dismissed));
        buckets.add(sessionsToConduct(evaluatorId, orgWide, dismissed));
        buckets.add(pendingAcknowledgments(evaluatorId, orgWide));
        buckets.add(overdueEvaluations(evaluatorId, orgWide));
        buckets.add(i983InFlight(evaluatorId, orgWide, dismissed));

        long total = buckets.stream().mapToLong(TodoBucket::count).sum();
        boolean hasNew = computeHasNew(buckets, lastSeenAt);
        return new TodoPanelResponse(
                "EVALUATOR", buckets, total, hasNew, lastSeenAt);
    }

    // ── Buckets ────────────────────────────────────────────────────────

    private TodoBucket pendingVivas(UUID evaluatorId, boolean orgWide,
                                    Set<String> dismissed) {
        String actionUrl = "/careers/evaluator/active-evaluees";
        String scope = orgWide ? "" : " AND (il.evaluator_id = ? OR il.evaluator_id IS NULL) ";
        Object[] countParams = orgWide ? new Object[0] : new Object[]{evaluatorId};
        long count = countOrZero(
                "SELECT COUNT(*) FROM projects p "
                        + "  JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                        + " WHERE p.status = 'PENDING_VIVA' " + scope,
                countParams);
        List<TodoItem> items = new ArrayList<>();
        if (count > 0) {
            Object[] listParams = orgWide ? new Object[]{TOP_N}
                    : new Object[]{evaluatorId, TOP_N};
            for (Map<String, Object> r : safeQueryForList(
                    "SELECT p.id AS p_id, p.title AS project_title, u.full_name, "
                            + "       p.submitted_at "
                            + "  FROM projects p "
                            + "  JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                            + "  JOIN users u ON u.id = il.user_id "
                            + " WHERE p.status = 'PENDING_VIVA' " + scope
                            + " ORDER BY p.submitted_at ASC NULLS LAST "
                            + " LIMIT ?", listParams)) {
                UUID pid = uuid(r.get("p_id"));
                String key = "EVALUATOR_PENDING_VIVA:" + pid;
                items.add(new TodoItem(
                        key,
                        strOrDash(r.get("full_name")),
                        strOrDash(r.get("project_title")),
                        actionUrl,
                        instantOf(r.get("submitted_at")),
                        dismissed.contains(key)));
            }
        }
        return new TodoBucket(
                "EVALUATOR_PENDING_VIVA",
                "Awaiting final viva",
                "video",
                count,
                actionUrl,
                severity(count, 3, 1),
                items);
    }

    private TodoBucket sessionsToConduct(UUID evaluatorId, boolean orgWide,
                                         Set<String> dismissed) {
        String actionUrl = "/careers/evaluator/pending-evaluations";
        String scope = orgWide ? "" : " AND ev.evaluator_id = ? ";
        Object[] countParams = orgWide ? new Object[0] : new Object[]{evaluatorId};
        long count = countOrZero(
                "SELECT COUNT(*) FROM intern_evaluations ev "
                        + " WHERE ev.status IN ('SCHEDULED','IN_PROGRESS') " + scope,
                countParams);
        List<TodoItem> items = new ArrayList<>();
        if (count > 0) {
            Object[] listParams = orgWide ? new Object[]{TOP_N}
                    : new Object[]{evaluatorId, TOP_N};
            for (Map<String, Object> r : safeQueryForList(
                    "SELECT ev.id AS ev_id, u.full_name, "
                            + "       ev.evaluation_type, ev.scheduled_for "
                            + "  FROM intern_evaluations ev "
                            + "  JOIN intern_lifecycles il ON il.id = ev.intern_lifecycle_id "
                            + "  JOIN users u ON u.id = il.user_id "
                            + " WHERE ev.status IN ('SCHEDULED','IN_PROGRESS') " + scope
                            + " ORDER BY ev.scheduled_for ASC NULLS LAST "
                            + " LIMIT ?", listParams)) {
                UUID evId = uuid(r.get("ev_id"));
                String key = "EVALUATOR_SESSION:" + evId;
                items.add(new TodoItem(
                        key,
                        strOrDash(r.get("full_name")),
                        strOrDash(r.get("evaluation_type")),
                        actionUrl,
                        instantOf(r.get("scheduled_for")),
                        dismissed.contains(key)));
            }
        }
        return new TodoBucket(
                "EVALUATOR_SESSION",
                "Sessions to conduct",
                "calendar-plus",
                count,
                actionUrl,
                severity(count, 5, 1),
                items);
    }

    private TodoBucket pendingAcknowledgments(UUID evaluatorId, boolean orgWide) {
        String actionUrl = "/careers/evaluator/evaluation-history?filter=unacknowledged";
        String scope = orgWide ? "" : " AND ev.evaluator_id = ? ";
        Object[] countParams = orgWide ? new Object[0] : new Object[]{evaluatorId};
        long count = countOrZero(
                "SELECT COUNT(*) FROM intern_evaluations ev "
                        + " WHERE ev.status = 'PUBLISHED' "
                        + "   AND ev.intern_acknowledged_at IS NULL "
                        + "   AND ev.published_at > NOW() - INTERVAL '14 days' "
                        + scope,
                countParams);
        return new TodoBucket(
                "EVALUATOR_PENDING_ACK",
                "Pending acknowledgments",
                "check-square",
                count,
                actionUrl,
                severity(count, 5, 1),
                List.of());
    }

    private TodoBucket overdueEvaluations(UUID evaluatorId, boolean orgWide) {
        String actionUrl = "/careers/evaluator/active-evaluees?filter=overdue";
        LocalDate monthStart = YearMonth.now().atDay(1);
        String scope = orgWide ? "" : " AND il.evaluator_id = ? ";
        Object[] params;
        if (orgWide) {
            params = new Object[]{monthStart.toString()};
        } else {
            params = new Object[]{monthStart.toString(), evaluatorId};
        }
        long count = countOrZero(
                "SELECT COUNT(*) FROM intern_lifecycles il "
                        + " WHERE il.active_status = 'ACTIVE' "
                        + "   AND NOT EXISTS ( "
                        + "       SELECT 1 FROM intern_evaluations ev "
                        + "        WHERE ev.intern_lifecycle_id = il.id "
                        + "          AND ev.status = 'PUBLISHED' "
                        + "          AND ev.published_at >= ?::timestamp "
                        + "   ) " + scope,
                params);
        return new TodoBucket(
                "EVALUATOR_OVERDUE_EVAL",
                "Overdue evaluations this month",
                "alert-triangle",
                count,
                actionUrl,
                count > 0 ? "URGENT" : "INFO",
                List.of());
    }

    private TodoBucket i983InFlight(UUID evaluatorId, boolean orgWide,
                                    Set<String> dismissed) {
        String actionUrl = "/careers/evaluator/i983-evaluations";
        String scope = orgWide ? "" : " AND i9.evaluator_id = ? ";
        Object[] countParams = orgWide ? new Object[0] : new Object[]{evaluatorId};
        long count = countOrZero(
                "SELECT COUNT(*) FROM i983_evaluations i9 "
                        + " WHERE i9.status IN ('SCHEDULED','IN_PROGRESS') " + scope,
                countParams);
        List<TodoItem> items = new ArrayList<>();
        if (count > 0) {
            Object[] listParams = orgWide ? new Object[]{TOP_N}
                    : new Object[]{evaluatorId, TOP_N};
            for (Map<String, Object> r : safeQueryForList(
                    "SELECT i9.id AS i9_id, u.full_name, i9.status, i9.scheduled_for "
                            + "  FROM i983_evaluations i9 "
                            + "  JOIN intern_lifecycles il ON il.id = i9.intern_lifecycle_id "
                            + "  JOIN users u ON u.id = il.user_id "
                            + " WHERE i9.status IN ('SCHEDULED','IN_PROGRESS') " + scope
                            + " ORDER BY i9.scheduled_for ASC NULLS LAST "
                            + " LIMIT ?", listParams)) {
                UUID id = uuid(r.get("i9_id"));
                String key = "EVALUATOR_I983:" + id;
                items.add(new TodoItem(
                        key,
                        strOrDash(r.get("full_name")),
                        strOrDash(r.get("status")),
                        actionUrl,
                        instantOf(r.get("scheduled_for")),
                        dismissed.contains(key)));
            }
        }
        return new TodoBucket(
                "EVALUATOR_I983",
                "I-983 evaluations",
                "file-text",
                count,
                actionUrl,
                severity(count, 3, 1),
                items);
    }

    // ── Helpers ────────────────────────────────────────────────────────

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
            log.debug("[EvaluatorTodo] count fallback ({}): {}", sql, e.getMessage());
            return 0L;
        }
    }

    private List<Map<String, Object>> safeQueryForList(String sql, Object... params) {
        try {
            return jdbc.queryForList(sql, params);
        } catch (Exception e) {
            log.debug("[EvaluatorTodo] queryForList fallback ({}): {}", sql, e.getMessage());
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
