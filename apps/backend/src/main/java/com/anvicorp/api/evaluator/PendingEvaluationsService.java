package com.anvicorp.api.evaluator;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Evaluator Phase 2 — pending evaluations queue (2 tabs). */
@Service
@RequiredArgsConstructor
@Slf4j
public class PendingEvaluationsService {

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public EvaluationWorkflowDtos.PendingEvaluationsResponse list(User caller) {
        boolean orgWide = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);
        UUID evaluatorId = caller.getId();

        List<EvaluationWorkflowDtos.ScheduledRow> scheduled = new ArrayList<>();
        try {
            String sql = "SELECT ev.id, ev.intern_lifecycle_id, "
                    + "u.full_name AS intern_name, il.employee_id, "
                    + "ev.evaluation_type, ev.status, ev.scheduled_for, "
                    + "ev.duration_minutes, ev.zoom_join_url "
                    + "FROM intern_evaluations ev "
                    + "JOIN intern_lifecycles il ON il.id = ev.intern_lifecycle_id "
                    + "JOIN users u ON u.id = il.user_id "
                    + "WHERE ev.status IN ('SCHEDULED','IN_PROGRESS') "
                    + (orgWide ? "" : "  AND ev.evaluator_id = ? ")
                    + "ORDER BY ev.scheduled_for ASC NULLS LAST";
            Object[] params = orgWide ? new Object[0] : new Object[]{evaluatorId};
            scheduled = jdbc.query(sql, params, (rs, n) ->
                    new EvaluationWorkflowDtos.ScheduledRow(
                            UUID.fromString(rs.getString("id")),
                            UUID.fromString(rs.getString("intern_lifecycle_id")),
                            rs.getString("intern_name"),
                            rs.getString("employee_id"),
                            rs.getString("evaluation_type"),
                            rs.getString("status"),
                            rs.getTimestamp("scheduled_for") != null
                                    ? rs.getTimestamp("scheduled_for").toInstant() : null,
                            (Integer) rs.getObject("duration_minutes"),
                            rs.getString("zoom_join_url")));
        } catch (Exception e) {
            log.warn("[PendingEvaluations] scheduled query failed: {}", e.getMessage());
        }

        List<EvaluationWorkflowDtos.AwaitingAckRow> awaiting = new ArrayList<>();
        try {
            String sql = "SELECT ev.id, ev.intern_lifecycle_id, "
                    + "u.full_name AS intern_name, il.employee_id, "
                    + "ev.evaluation_type, ev.published_at "
                    + "FROM intern_evaluations ev "
                    + "JOIN intern_lifecycles il ON il.id = ev.intern_lifecycle_id "
                    + "JOIN users u ON u.id = il.user_id "
                    + "WHERE ev.status = 'PUBLISHED' "
                    + "  AND ev.intern_acknowledged_at IS NULL "
                    + (orgWide ? "" : "  AND ev.evaluator_id = ? ")
                    + "ORDER BY ev.published_at ASC NULLS LAST";
            Object[] params = orgWide ? new Object[0] : new Object[]{evaluatorId};
            awaiting = jdbc.query(sql, params, (rs, n) -> {
                Instant pubAt = rs.getTimestamp("published_at") != null
                        ? rs.getTimestamp("published_at").toInstant() : null;
                int daysPending = pubAt != null
                        ? (int) ChronoUnit.DAYS.between(pubAt, Instant.now())
                        : 0;
                return new EvaluationWorkflowDtos.AwaitingAckRow(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("intern_lifecycle_id")),
                        rs.getString("intern_name"),
                        rs.getString("employee_id"),
                        rs.getString("evaluation_type"),
                        pubAt,
                        daysPending);
            });
        } catch (Exception e) {
            log.warn("[PendingEvaluations] awaiting query failed: {}", e.getMessage());
        }

        return new EvaluationWorkflowDtos.PendingEvaluationsResponse(scheduled, awaiting);
    }
}
