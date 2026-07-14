package com.anvicorp.api.evaluator;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.ForbiddenException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Evaluator's pending-Q&A queue. Lists projects whose status is
 * {@code PENDING_VIVA} (trainer has approved, awaiting the evaluator's
 * Q&A session + final approval) scoped to the calling EVALUATOR.
 *
 * <p>Scoping mirrors {@link EvaluatorScopeGuard} — single-evaluator-org
 * default means {@code intern_lifecycles.evaluator_id} can be null and
 * any EVALUATOR is the de-facto owner; {@code SUPER_ADMIN} sees org-wide.
 * Without the null fallback the queue would silently exclude interns
 * whose evaluator_id was never stamped at offer-sign time.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PendingVivasService {

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public PendingVivasDtos.PendingVivasResponse list(User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required");
        boolean orgWide = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);
        boolean isEvaluator = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.EVALUATOR);
        if (!orgWide && !isEvaluator) {
            throw new ForbiddenException("EVALUATOR or SUPER_ADMIN required");
        }

        // Project + intern. Scope by lifecycle.evaluator_id with the
        // single-evaluator null fallback. Latest submission's trainer
        // feedback is surfaced inline (sub-select; the queue is small).
        StringBuilder where = new StringBuilder(
                " WHERE p.status = 'PENDING_VIVA' ");
        List<Object> params = new ArrayList<>();
        if (!orgWide) {
            where.append(" AND (il.evaluator_id = ? OR il.evaluator_id IS NULL) ");
            params.add(caller.getId());
        }

        String sql = "SELECT p.id AS project_id, p.title AS project_title, "
                + "       p.tech_stack, p.month_year, p.project_number, "
                + "       p.intern_lifecycle_id, il.user_id AS intern_user_id, "
                + "       il.employee_id, u.full_name AS intern_name, "
                + "       p.submitted_at, "
                + "       (SELECT s.trainer_feedback FROM project_submissions s "
                + "           WHERE s.project_id = p.id "
                + "           ORDER BY s.submitted_at DESC LIMIT 1) AS trainer_feedback "
                + " FROM projects p "
                + " JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                + " JOIN users u ON u.id = il.user_id "
                + where
                + " ORDER BY p.submitted_at ASC NULLS LAST";

        List<PendingVivasDtos.PendingVivaRow> rows = new ArrayList<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(sql, params.toArray())) {
                UUID projectId = uuid(r.get("project_id"));
                if (projectId == null) continue;
                Instant submitted = toInstant(r.get("submitted_at"));
                long hoursWaiting = submitted == null ? 0
                        : Math.max(0, Duration.between(submitted, Instant.now()).toHours());
                rows.add(new PendingVivasDtos.PendingVivaRow(
                        projectId,
                        (String) r.get("project_title"),
                        (String) r.get("tech_stack"),
                        (String) r.get("month_year"),
                        shortVal(r.get("project_number")),
                        uuid(r.get("intern_lifecycle_id")),
                        uuid(r.get("intern_user_id")),
                        (String) r.get("intern_name"),
                        (String) r.get("employee_id"),
                        submitted,
                        hoursWaiting,
                        (String) r.get("trainer_feedback"),
                        loadActiveSession(projectId)));
            }
        } catch (Exception e) {
            log.warn("[PendingVivas] list query failed: {}", e.getMessage());
        }
        return new PendingVivasDtos.PendingVivasResponse(rows, rows.size());
    }

    private PendingVivasDtos.ActiveSession loadActiveSession(UUID projectId) {
        // Most-recent SCHEDULED or CONDUCTED session, if any. Returning +
        // completed sessions don't surface here — they're history.
        String sql = "SELECT id, status, scheduled_at, meeting_link, "
                + "       zoom_meeting_id, zoom_join_url, zoom_start_url "
                + "FROM qa_sessions "
                + "WHERE project_id = ? "
                + "  AND status IN ('SCHEDULED', 'CONDUCTED') "
                + "ORDER BY scheduled_at DESC, created_at DESC "
                + "LIMIT 1";
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(sql, projectId);
            if (rows.isEmpty()) return null;
            Map<String, Object> r = rows.get(0);
            return new PendingVivasDtos.ActiveSession(
                    uuid(r.get("id")),
                    (String) r.get("status"),
                    toInstant(r.get("scheduled_at")),
                    (String) r.get("meeting_link"),
                    (String) r.get("zoom_meeting_id"),
                    (String) r.get("zoom_join_url"),
                    (String) r.get("zoom_start_url"));
        } catch (Exception e) {
            log.debug("[PendingVivas] active-session lookup failed for {}: {}",
                    projectId, e.getMessage());
            return null;
        }
    }

    private static UUID uuid(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        try { return UUID.fromString(String.valueOf(o)); }
        catch (Exception e) { return null; }
    }

    private static Short shortVal(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.shortValue();
        try { return Short.parseShort(String.valueOf(o)); }
        catch (Exception e) { return null; }
    }

    private static Instant toInstant(Object o) {
        if (o == null) return null;
        if (o instanceof Instant i) return i;
        if (o instanceof java.sql.Timestamp t) return t.toInstant();
        return null;
    }
}
