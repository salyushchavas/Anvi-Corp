package com.anvicorp.api.evaluator;

import com.anvicorp.api.common.MonthRange;
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
    public EvaluationWorkflowDtos.PendingEvaluationsResponse list(User caller, MonthRange range) {
        boolean orgWide = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);
        UUID evaluatorId = caller.getId();
        // Month scoping — current-month returns the byte-identical prior
        // queue (no filter). For a past month, restrict scheduled rows to
        // those with scheduled_for in that month, and awaiting-ack rows to
        // those with published_at in that month.
        MonthRange scope = range != null ? range : MonthRange.parse(null);
        boolean isCurrent = scope.isCurrent();

        List<EvaluationWorkflowDtos.ScheduledRow> scheduled = new ArrayList<>();
        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT ev.id, ev.intern_lifecycle_id, "
                    + "u.full_name AS intern_name, il.employee_id, "
                    + "ev.evaluation_type, ev.status, ev.scheduled_for, "
                    + "ev.duration_minutes, ev.zoom_join_url, "
                    + "ev.session_group_id, p.title AS linked_project_title "
                    + "FROM intern_evaluations ev "
                    + "JOIN intern_lifecycles il ON il.id = ev.intern_lifecycle_id "
                    + "JOIN users u ON u.id = il.user_id "
                    + "LEFT JOIN projects p ON p.id = ev.linked_project_id "
                    + "WHERE ev.status IN ('SCHEDULED','IN_PROGRESS') ");
            List<Object> params = new ArrayList<>();
            if (!orgWide) {
                sql.append("  AND ev.evaluator_id = ? ");
                params.add(evaluatorId);
            }
            if (!isCurrent) {
                sql.append("  AND ev.scheduled_for >= ?::timestamp "
                        + "  AND ev.scheduled_for <  ?::timestamp ");
                params.add(scope.startDateString()); params.add(scope.endDateString());
            }
            sql.append("ORDER BY ev.scheduled_for ASC NULLS LAST");
            scheduled = jdbc.query(sql.toString(), params.toArray(), (rs, n) -> {
                String groupIdRaw = rs.getString("session_group_id");
                return new EvaluationWorkflowDtos.ScheduledRow(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("intern_lifecycle_id")),
                        rs.getString("intern_name"),
                        rs.getString("employee_id"),
                        rs.getString("evaluation_type"),
                        rs.getString("status"),
                        rs.getTimestamp("scheduled_for") != null
                                ? rs.getTimestamp("scheduled_for").toInstant() : null,
                        (Integer) rs.getObject("duration_minutes"),
                        rs.getString("zoom_join_url"),
                        groupIdRaw != null ? UUID.fromString(groupIdRaw) : null,
                        rs.getString("linked_project_title"));
            });
        } catch (Exception e) {
            log.warn("[PendingEvaluations] scheduled query failed: {}", e.getMessage());
        }

        List<EvaluationWorkflowDtos.AwaitingAckRow> awaiting = new ArrayList<>();
        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT ev.id, ev.intern_lifecycle_id, "
                    + "u.full_name AS intern_name, il.employee_id, "
                    + "ev.evaluation_type, ev.published_at "
                    + "FROM intern_evaluations ev "
                    + "JOIN intern_lifecycles il ON il.id = ev.intern_lifecycle_id "
                    + "JOIN users u ON u.id = il.user_id "
                    + "WHERE ev.status = 'PUBLISHED' "
                    + "  AND ev.intern_acknowledged_at IS NULL ");
            List<Object> params = new ArrayList<>();
            if (!orgWide) {
                sql.append("  AND ev.evaluator_id = ? ");
                params.add(evaluatorId);
            }
            if (!isCurrent) {
                sql.append("  AND ev.published_at >= ?::timestamp "
                        + "  AND ev.published_at <  ?::timestamp ");
                params.add(scope.startDateString()); params.add(scope.endDateString());
            }
            sql.append("ORDER BY ev.published_at ASC NULLS LAST");
            awaiting = jdbc.query(sql.toString(), params.toArray(), (rs, n) -> {
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
