package com.anvicorp.api.erm.exit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ERM Phase 7 — surfaces active interns whose signals indicate the
 * engagement is ready to wrap up:
 * <ul>
 *   <li>All ongoing projects COMPLETED &gt; 14 days with no new
 *       assignment.</li>
 *   <li>Last MONTHLY evaluation &gt; 60 days ago.</li>
 *   <li>Last weekly_meeting &gt; 21 days.</li>
 *   <li>(Future) engagement in its final 30 days based on tentative
 *       start + expected duration — deferred until duration is tracked.</li>
 * </ul>
 *
 * <p>Pure read service — no writes. Suggested exit_type defaults to
 * COMPLETED; ERM can override on initiate.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReadyToExitDetector {

    private final JdbcTemplate jdbc;

    public record Row(
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String employeeId,
            int daysActive,
            String suggestedExitType,
            List<String> signals
    ) {}

    public List<Row> detect(String scope, UUID callerId, int limit) {
        // Single SQL that returns the candidate set + per-signal booleans
        // we evaluate in Java to keep the SQL readable. The set is small
        // (active interns) so a small fan-out post-query is fine.
        StringBuilder sql = new StringBuilder()
                .append("SELECT il.id AS lifecycle_id, il.user_id, u.full_name, il.employee_id, ")
                .append("       EXTRACT(EPOCH FROM (NOW() - il.started_at))/86400 AS days_active, ")
                .append("       (SELECT MAX(pa.assignment_date) FROM project_assignments pa ")
                .append("          WHERE pa.intern_id = il.user_id) AS last_assignment, ")
                .append("       (SELECT COUNT(*) FROM project_assignments pa ")
                .append("          WHERE pa.intern_id = il.user_id ")
                .append("            AND pa.status IN ('ASSIGNED','IN_PROGRESS','SUBMITTED','RETURNED')) AS active_projects, ")
                .append("       (SELECT MAX(ie.published_at) FROM intern_evaluations ie ")
                .append("          WHERE ie.intern_lifecycle_id = il.id ")
                .append("            AND ie.evaluation_type = 'MONTHLY' ")
                .append("            AND ie.status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED')) AS last_monthly_eval, ")
                .append("       (SELECT MAX(wm.scheduled_for) FROM weekly_meetings wm ")
                .append("          WHERE wm.intern_lifecycle_id = il.id ")
                .append("            AND wm.status = 'COMPLETED') AS last_meeting ")
                .append("  FROM intern_lifecycles il ")
                .append("  JOIN users u ON u.id = il.user_id ")
                .append(" WHERE il.active_status = 'ACTIVE' ");
        List<Object> params = new ArrayList<>();
        if ("mine".equalsIgnoreCase(scope) && callerId != null) {
            sql.append(" AND (il.erm_id IS NULL OR il.erm_id = ?) ");
            params.add(callerId);
        }
        sql.append(" ORDER BY u.full_name ASC LIMIT ").append(Math.min(500, Math.max(1, limit)));

        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(sql.toString(), params.toArray());
        } catch (Exception e) {
            log.warn("[ReadyToExit] query failed (non-fatal): {}", e.getMessage());
            return List.of();
        }

        List<Row> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            List<String> sigs = new ArrayList<>();
            int activeProjects = ((Number) row.getOrDefault("active_projects", 0)).intValue();
            java.sql.Date lastAssign = (java.sql.Date) row.get("last_assignment");
            java.sql.Timestamp lastEval = (java.sql.Timestamp) row.get("last_monthly_eval");
            java.sql.Timestamp lastMeeting = (java.sql.Timestamp) row.get("last_meeting");
            long now = System.currentTimeMillis();

            if (activeProjects == 0 && lastAssign != null) {
                long days = (now - lastAssign.getTime()) / (1000L * 86400L);
                if (days > 14) {
                    sigs.add("Last project assigned " + days + " days ago, no active projects");
                }
            }
            if (lastEval != null) {
                long days = (now - lastEval.getTime()) / (1000L * 86400L);
                if (days > 60) {
                    sigs.add("Last monthly evaluation " + days + " days ago");
                }
            } else {
                sigs.add("No monthly evaluation on record");
            }
            if (lastMeeting != null) {
                long days = (now - lastMeeting.getTime()) / (1000L * 86400L);
                if (days > 21) {
                    sigs.add("Last weekly meeting " + days + " days ago");
                }
            }

            if (sigs.isEmpty()) continue;

            int daysActive = ((Number) row.getOrDefault("days_active", 0)).intValue();
            String suggestedType = activeProjects == 0 ? "COMPLETED" : "EXTENDED";
            out.add(new Row(
                    asUuid(row.get("lifecycle_id")),
                    asUuid(row.get("user_id")),
                    (String) row.get("full_name"),
                    (String) row.get("employee_id"),
                    Math.max(0, daysActive),
                    suggestedType,
                    sigs));
        }
        return out;
    }

    private static UUID asUuid(Object o) {
        if (o == null) return null;
        try { return UUID.fromString(String.valueOf(o)); } catch (Exception e) { return null; }
    }
}
