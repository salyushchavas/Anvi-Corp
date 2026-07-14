package com.anvicorp.api.manager;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Manager Phase 3A — Active Interns health view. Portfolio-wide for
 * MANAGER + SUPER_ADMIN; manager_id is an owner column + an optional
 * "My interns" filter, not an access fence.
 *
 * <p>Population: {@code users.lifecycle_status = 'ACTIVE_INTERN'}.
 * Per-intern card states (project / trainer-meeting / monthly
 * evaluation / weekly timesheet) computed inline via correlated
 * subqueries against the same tables the ERM Active Intern Monitor
 * uses, with the same thresholds so the two views never disagree.</p>
 *
 * <p>Sensitive fields stay off the wire: published evaluation
 * {@code overall_score} + {@code recommendation} are returned
 * (manager-visible per the permission matrix); the evaluator's
 * {@code internal_notes} is never read by this service.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerActiveInternsService {

    /** Match ErmThresholds.TRAINER_MEETING_MISSING_DAYS so Manager and ERM
     *  show the same "at risk" set. */
    private static final int TRAINER_MEETING_MISSING_DAYS = 7;
    /** Match ErmThresholds.EVAL_OVERDUE_DAYS. */
    private static final int EVAL_OVERDUE_DAYS = 35;

    private final JdbcTemplate jdbc;

    public ManagerDtos.ActiveInternResponse list(
            User caller,
            String technology,
            UUID trainerId,
            UUID evaluatorId,
            UUID ermOwner,
            UUID managerId,        // explicit filter ("My interns" sets this to caller.id)
            String health,         // ON_TRACK | AT_RISK | null
            String search,
            int page,
            int pageSize) {

        if (caller == null || caller.getRoles() == null
                || (!caller.getRoles().contains(UserRole.MANAGER)
                    && !caller.getRoles().contains(UserRole.SUPER_ADMIN))) {
            throw new SecurityException("Manager or SUPER_ADMIN required");
        }

        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, pageSize), 100);
        int offset = safePage * safeSize;

        StringBuilder where = new StringBuilder(
                " WHERE u.lifecycle_status = 'ACTIVE_INTERN' ");
        List<Object> params = new ArrayList<>();

        if (managerId != null) {
            where.append(" AND il.manager_id = ? ");
            params.add(managerId);
        }
        if (trainerId != null) {
            where.append(" AND il.trainer_id = ? ");
            params.add(trainerId);
        }
        if (evaluatorId != null) {
            where.append(" AND il.evaluator_id = ? ");
            params.add(evaluatorId);
        }
        if (ermOwner != null) {
            where.append(" AND il.erm_id = ? ");
            params.add(ermOwner);
        }
        if (technology != null && !technology.isBlank()) {
            // Read from the JobPosting the intern applied to (most recent).
            where.append(" AND EXISTS (")
                    .append("    SELECT 1 FROM applications a ")
                    .append("    JOIN candidates c2 ON c2.id = a.candidate_id ")
                    .append("    JOIN job_postings jp ON jp.id = a.job_posting_id ")
                    .append("    WHERE c2.user_id = u.id ")
                    .append("      AND LOWER(COALESCE(jp.technology,'')) = LOWER(?)) ");
            params.add(technology);
        }
        if (search != null && !search.isBlank()) {
            String like = "%" + search.trim().toLowerCase() + "%";
            where.append(" AND (LOWER(u.full_name) LIKE ? "
                    + " OR LOWER(u.email) LIKE ? "
                    + " OR LOWER(COALESCE(il.employee_id,'')) LIKE ?) ");
            params.add(like); params.add(like); params.add(like);
        }

        String fromAndJoins = ""
                + "  FROM users u "
                + "  JOIN intern_lifecycles il ON il.user_id = u.id ";

        long total = 0L;
        try {
            Long v = jdbc.queryForObject(
                    "SELECT COUNT(*) " + fromAndJoins + where,
                    Long.class, params.toArray());
            total = v != null ? v : 0L;
        } catch (Exception e) {
            log.warn("[ManagerActiveInterns] count failed: {}", e.getMessage());
        }

        String sql = ""
                + "SELECT u.id AS user_id, u.full_name, u.email, "
                + "       il.id AS lifecycle_id, il.employee_id, il.started_at, "
                + "       il.trainer_id, il.evaluator_id, il.manager_id, il.erm_id, "
                + "       (SELECT t.full_name FROM users t WHERE t.id = il.trainer_id) AS trainer_name, "
                + "       (SELECT e.full_name FROM users e WHERE e.id = il.evaluator_id) AS evaluator_name, "
                + "       (SELECT m.full_name FROM users m WHERE m.id = il.manager_id) AS manager_name, "
                + "       (SELECT r.full_name FROM users r WHERE r.id = il.erm_id) AS erm_owner_name, "
                // Work auth + technology
                + "       war.work_auth_type, "
                + "       (SELECT jp.technology FROM applications a "
                + "          JOIN candidates c2 ON c2.id = a.candidate_id "
                + "          JOIN job_postings jp ON jp.id = a.job_posting_id "
                + "         WHERE c2.user_id = u.id "
                + "         ORDER BY a.applied_at DESC LIMIT 1) AS technology, "
                // Project — most-recent active assignment
                + "       (SELECT pa.status FROM project_assignments pa "
                + "         WHERE pa.intern_id = u.id "
                + "           AND pa.status IN ('ASSIGNED','IN_PROGRESS','SUBMITTED','RETURNED') "
                + "         ORDER BY pa.assignment_date DESC LIMIT 1) AS project_status, "
                + "       (SELECT p.title FROM project_assignments pa "
                + "         JOIN projects p ON p.id = pa.project_id "
                + "         WHERE pa.intern_id = u.id "
                + "           AND pa.status IN ('ASSIGNED','IN_PROGRESS','SUBMITTED','RETURNED') "
                + "         ORDER BY pa.assignment_date DESC LIMIT 1) AS project_title, "
                + "       (SELECT pa.due_date FROM project_assignments pa "
                + "         WHERE pa.intern_id = u.id "
                + "           AND pa.status IN ('ASSIGNED','IN_PROGRESS','SUBMITTED','RETURNED') "
                + "         ORDER BY pa.assignment_date DESC LIMIT 1) AS project_due_date, "
                // Meeting — last SCHEDULED / COMPLETED row
                + "       (SELECT wm.status FROM weekly_meetings wm "
                + "         WHERE wm.intern_lifecycle_id = il.id "
                + "           AND wm.status IN ('SCHEDULED','COMPLETED') "
                + "         ORDER BY wm.scheduled_for DESC LIMIT 1) AS last_meeting_status, "
                + "       (SELECT wm.scheduled_for FROM weekly_meetings wm "
                + "         WHERE wm.intern_lifecycle_id = il.id "
                + "           AND wm.status IN ('SCHEDULED','COMPLETED') "
                + "         ORDER BY wm.scheduled_for DESC LIMIT 1) AS last_meeting_at, "
                // Evaluation — latest MONTHLY in published-class status
                + "       (SELECT ie.status FROM intern_evaluations ie "
                + "         WHERE ie.intern_lifecycle_id = il.id "
                + "           AND ie.evaluation_type = 'MONTHLY' "
                + "           AND ie.status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED') "
                + "         ORDER BY ie.published_at DESC LIMIT 1) AS last_eval_status, "
                + "       (SELECT ie.published_at FROM intern_evaluations ie "
                + "         WHERE ie.intern_lifecycle_id = il.id "
                + "           AND ie.evaluation_type = 'MONTHLY' "
                + "           AND ie.status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED') "
                + "         ORDER BY ie.published_at DESC LIMIT 1) AS last_eval_at, "
                + "       (SELECT ie.overall_score FROM intern_evaluations ie "
                + "         WHERE ie.intern_lifecycle_id = il.id "
                + "           AND ie.evaluation_type = 'MONTHLY' "
                + "           AND ie.status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED') "
                + "         ORDER BY ie.published_at DESC LIMIT 1) AS last_eval_score, "
                + "       (SELECT ie.recommendation FROM intern_evaluations ie "
                + "         WHERE ie.intern_lifecycle_id = il.id "
                + "           AND ie.evaluation_type = 'MONTHLY' "
                + "           AND ie.status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED') "
                + "         ORDER BY ie.published_at DESC LIMIT 1) AS last_eval_recommendation, "
                // Timesheet — current week + previous week + recent rejections.
                // timesheets.intern_id joins candidates(id); reach via the
                // candidates row for this user (most recent if duplicates).
                + "       (SELECT ts.status FROM timesheets ts "
                + "          JOIN candidates c3 ON c3.id = ts.intern_id "
                + "         WHERE c3.user_id = u.id "
                + "           AND ts.week_start = (date_trunc('week', NOW())::date) "
                + "         ORDER BY ts.created_at DESC LIMIT 1) AS current_week_status, "
                + "       (SELECT ts.status FROM timesheets ts "
                + "          JOIN candidates c3 ON c3.id = ts.intern_id "
                + "         WHERE c3.user_id = u.id "
                + "           AND ts.week_start = (date_trunc('week', NOW())::date - INTERVAL '7 days') "
                + "         ORDER BY ts.created_at DESC LIMIT 1) AS previous_week_status, "
                + "       (SELECT COUNT(*) FROM timesheets ts "
                + "          JOIN candidates c3 ON c3.id = ts.intern_id "
                + "         WHERE c3.user_id = u.id "
                + "           AND ts.status = 'REJECTED' "
                + "           AND ts.week_start > CURRENT_DATE - INTERVAL '28 days') AS recent_rejections "
                + fromAndJoins
                + "  LEFT JOIN work_authorization_records war ON war.user_id = u.id "
                + where
                + " ORDER BY u.full_name ASC "
                + " LIMIT " + safeSize + " OFFSET " + offset;

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<ManagerDtos.ActiveInternRow> rows = new ArrayList<>();
        try {
            rows = jdbc.query(sql, params.toArray(), (rs, n) -> mapRow(rs, today));
        } catch (Exception e) {
            log.warn("[ManagerActiveInterns] list failed: {}", e.getMessage());
        }

        // Apply the optional health filter in memory — the per-row at-risk
        // computation already happened during the SQL hydration; filtering
        // in memory keeps the query simple and the total count stays
        // honest at the SQL level.
        if (health != null && !health.isBlank()) {
            String target = health.toUpperCase();
            int before = rows.size();
            rows.removeIf(r -> !target.equals(r.health()));
            // When the in-memory filter trims rows, totalElements is no longer
            // strictly accurate — recompute from the trimmed list so the UI
            // pagination doesn't claim more pages than it can show.
            if (rows.size() != before) {
                total = rows.size();
            }
        }

        int totalPages = safeSize == 0 ? 0 : (int) Math.ceil(total / (double) safeSize);
        return new ManagerDtos.ActiveInternResponse(
                rows, safePage, safeSize, total, totalPages);
    }

    public ManagerDtos.ActiveInternSummary summary(User caller) {
        long activeTotal = safeCount(
                "SELECT COUNT(*) FROM users WHERE lifecycle_status = 'ACTIVE_INTERN'");

        long noProject = safeCount(
                "SELECT COUNT(*) FROM users u "
                        + " WHERE u.lifecycle_status = 'ACTIVE_INTERN' "
                        + "   AND NOT EXISTS (SELECT 1 FROM project_assignments pa "
                        + "                     WHERE pa.intern_id = u.id "
                        + "                       AND pa.status IN ('ASSIGNED','IN_PROGRESS','SUBMITTED','RETURNED'))");

        long meetingMissing = safeCount(
                "SELECT COUNT(*) FROM users u "
                        + "  JOIN intern_lifecycles il ON il.user_id = u.id "
                        + " WHERE u.lifecycle_status = 'ACTIVE_INTERN' "
                        + "   AND il.trainer_id IS NOT NULL "
                        + "   AND NOT EXISTS (SELECT 1 FROM weekly_meetings wm "
                        + "                     WHERE wm.intern_lifecycle_id = il.id "
                        + "                       AND wm.status IN ('SCHEDULED','COMPLETED') "
                        + "                       AND wm.scheduled_for > NOW() - INTERVAL '"
                        + TRAINER_MEETING_MISSING_DAYS + " days')");

        long evalOverdue = safeCount(
                "SELECT COUNT(*) FROM users u "
                        + "  JOIN intern_lifecycles il ON il.user_id = u.id "
                        + "  LEFT JOIN ( "
                        + "    SELECT intern_lifecycle_id, MAX(published_at) AS pub "
                        + "      FROM intern_evaluations "
                        + "     WHERE evaluation_type = 'MONTHLY' "
                        + "       AND status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED') "
                        + "     GROUP BY intern_lifecycle_id "
                        + "  ) e ON e.intern_lifecycle_id = il.id "
                        + " WHERE u.lifecycle_status = 'ACTIVE_INTERN' "
                        + "   AND (e.pub IS NULL OR e.pub < NOW() - INTERVAL '"
                        + EVAL_OVERDUE_DAYS + " days')");

        long timesheetMissing = safeCount(
                "WITH last_week AS (SELECT (date_trunc('week', NOW()) - INTERVAL '7 days')::date AS week_start) "
                        + "SELECT COUNT(*) FROM users u "
                        + " WHERE u.lifecycle_status = 'ACTIVE_INTERN' "
                        + "   AND NOT EXISTS (SELECT 1 FROM timesheets ts "
                        + "                     JOIN candidates c ON c.id = ts.intern_id "
                        + "                     WHERE c.user_id = u.id "
                        + "                       AND ts.week_start = (SELECT week_start FROM last_week) "
                        + "                       AND ts.status IN ('SUBMITTED','APPROVED'))");

        // At-risk = any of (no project, meeting missing, eval overdue, timesheet missing).
        // Computed as the UNION of those four predicates so a row showing up
        // in multiple buckets is still counted once.
        long atRisk = safeCount(
                "SELECT COUNT(*) FROM users u "
                        + "  JOIN intern_lifecycles il ON il.user_id = u.id "
                        + "  LEFT JOIN ( "
                        + "    SELECT intern_lifecycle_id, MAX(published_at) AS pub "
                        + "      FROM intern_evaluations "
                        + "     WHERE evaluation_type = 'MONTHLY' "
                        + "       AND status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED') "
                        + "     GROUP BY intern_lifecycle_id "
                        + "  ) e ON e.intern_lifecycle_id = il.id "
                        + " WHERE u.lifecycle_status = 'ACTIVE_INTERN' "
                        + "   AND ( "
                        + "     NOT EXISTS (SELECT 1 FROM project_assignments pa "
                        + "                   WHERE pa.intern_id = u.id "
                        + "                     AND pa.status IN ('ASSIGNED','IN_PROGRESS','SUBMITTED','RETURNED')) "
                        + "     OR (il.trainer_id IS NOT NULL "
                        + "         AND NOT EXISTS (SELECT 1 FROM weekly_meetings wm "
                        + "                           WHERE wm.intern_lifecycle_id = il.id "
                        + "                             AND wm.status IN ('SCHEDULED','COMPLETED') "
                        + "                             AND wm.scheduled_for > NOW() - INTERVAL '"
                        + TRAINER_MEETING_MISSING_DAYS + " days')) "
                        + "     OR (e.pub IS NULL OR e.pub < NOW() - INTERVAL '"
                        + EVAL_OVERDUE_DAYS + " days') "
                        + "     OR NOT EXISTS (SELECT 1 FROM timesheets ts "
                        + "                      JOIN candidates c ON c.id = ts.intern_id "
                        + "                      WHERE c.user_id = u.id "
                        + "                        AND ts.week_start = (date_trunc('week', NOW()) - INTERVAL '7 days')::date "
                        + "                        AND ts.status IN ('SUBMITTED','APPROVED')) "
                        + "   )");

        long onTrack = Math.max(0, activeTotal - atRisk);

        return new ManagerDtos.ActiveInternSummary(
                activeTotal, onTrack, atRisk,
                noProject, meetingMissing, evalOverdue, timesheetMissing);
    }

    public ManagerDtos.ActiveInternFilterOptions filterOptions() {
        List<String> technologies = new ArrayList<>();
        try {
            jdbc.query(
                    "SELECT DISTINCT technology FROM job_postings "
                            + " WHERE technology IS NOT NULL AND technology <> '' "
                            + " ORDER BY technology",
                    rs -> { technologies.add(rs.getString(1)); });
        } catch (Exception e) {
            log.warn("[ManagerActiveInterns] technology distinct failed: {}", e.getMessage());
        }
        return new ManagerDtos.ActiveInternFilterOptions(
                technologies,
                distinctRoleAssignees("trainer_id"),
                distinctRoleAssignees("evaluator_id"),
                distinctRoleAssignees("manager_id"),
                distinctErmOwners());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private List<ManagerDtos.UserOption> distinctRoleAssignees(String columnName) {
        // Whitelist defensively even though the column comes from internal callers.
        if (!"trainer_id".equals(columnName)
                && !"evaluator_id".equals(columnName)
                && !"manager_id".equals(columnName)) {
            return List.of();
        }
        List<ManagerDtos.UserOption> out = new ArrayList<>();
        try {
            jdbc.query(
                    "SELECT DISTINCT u.id, u.full_name "
                            + "  FROM intern_lifecycles il "
                            + "  JOIN users u ON u.id = il." + columnName
                            + " WHERE il." + columnName + " IS NOT NULL "
                            + " ORDER BY u.full_name",
                    rs -> {
                        out.add(new ManagerDtos.UserOption(
                                UUID.fromString(rs.getString("id")),
                                rs.getString("full_name")));
                    });
        } catch (Exception e) {
            log.warn("[ManagerActiveInterns] distinct {} failed: {}", columnName, e.getMessage());
        }
        return out;
    }

    private List<ManagerDtos.ErmOwnerOption> distinctErmOwners() {
        List<ManagerDtos.ErmOwnerOption> out = new ArrayList<>();
        try {
            jdbc.query(
                    "SELECT DISTINCT u.id, u.full_name "
                            + "  FROM intern_lifecycles il "
                            + "  JOIN users u ON u.id = il.erm_id "
                            + " WHERE il.erm_id IS NOT NULL "
                            + " ORDER BY u.full_name",
                    rs -> {
                        out.add(new ManagerDtos.ErmOwnerOption(
                                UUID.fromString(rs.getString("id")),
                                rs.getString("full_name")));
                    });
        } catch (Exception e) {
            log.warn("[ManagerActiveInterns] distinct erm owners failed: {}", e.getMessage());
        }
        return out;
    }

    private ManagerDtos.ActiveInternRow mapRow(java.sql.ResultSet rs, LocalDate today)
            throws java.sql.SQLException {
        UUID lifecycleId = UUID.fromString(rs.getString("lifecycle_id"));
        UUID userId = UUID.fromString(rs.getString("user_id"));
        java.sql.Timestamp startedAtTs = rs.getTimestamp("started_at");

        // Project state
        String projectStatus = rs.getString("project_status");
        String projectTitle = rs.getString("project_title");
        java.sql.Date projectDueSql = rs.getDate("project_due_date");
        LocalDate projectDue = projectDueSql != null ? projectDueSql.toLocalDate() : null;
        boolean projectAtRisk = projectStatus == null
                || (projectDue != null && projectDue.isBefore(today)
                        && !"SUBMITTED".equals(projectStatus));
        ManagerDtos.ProjectState project = new ManagerDtos.ProjectState(
                projectStatus, projectTitle, projectDue, projectAtRisk);

        // Meeting state
        String lastMeetingStatus = rs.getString("last_meeting_status");
        java.sql.Timestamp lastMeetingTs = rs.getTimestamp("last_meeting_at");
        java.time.Instant lastMeetingAt = lastMeetingTs != null
                ? lastMeetingTs.toInstant() : null;
        Long daysSinceLast = lastMeetingAt != null
                ? ChronoUnit.DAYS.between(lastMeetingAt, java.time.Instant.now())
                : null;
        boolean meetingAtRisk = daysSinceLast == null
                || daysSinceLast > TRAINER_MEETING_MISSING_DAYS;
        ManagerDtos.MeetingState meeting = new ManagerDtos.MeetingState(
                lastMeetingStatus, lastMeetingAt, daysSinceLast, meetingAtRisk);

        // Evaluation state
        String lastEvalStatus = rs.getString("last_eval_status");
        java.sql.Timestamp lastEvalTs = rs.getTimestamp("last_eval_at");
        java.time.Instant lastEvalAt = lastEvalTs != null
                ? lastEvalTs.toInstant() : null;
        Object overallObj = rs.getObject("last_eval_score");
        Integer overall = overallObj instanceof Number n ? n.intValue() : null;
        String recommendation = rs.getString("last_eval_recommendation");
        Long daysSinceEval = lastEvalAt != null
                ? ChronoUnit.DAYS.between(lastEvalAt, java.time.Instant.now())
                : null;
        boolean evalAtRisk = daysSinceEval == null
                || daysSinceEval > EVAL_OVERDUE_DAYS;
        ManagerDtos.EvaluationState evaluation = new ManagerDtos.EvaluationState(
                lastEvalStatus, lastEvalAt, overall, recommendation,
                daysSinceEval, evalAtRisk);

        // Timesheet state
        String currentWeekStatus = rs.getString("current_week_status");
        String previousWeekStatus = rs.getString("previous_week_status");
        int recentRejections = rs.getInt("recent_rejections");
        boolean previousMissing = previousWeekStatus == null
                || (!"SUBMITTED".equals(previousWeekStatus)
                        && !"APPROVED".equals(previousWeekStatus));
        boolean tsAtRisk = previousMissing || recentRejections >= 2;
        ManagerDtos.TimesheetState timesheet = new ManagerDtos.TimesheetState(
                currentWeekStatus, previousWeekStatus, recentRejections, tsAtRisk);

        String health = (projectAtRisk || meetingAtRisk || evalAtRisk || tsAtRisk)
                ? "ACTIVE_AT_RISK" : "ACTIVE_ON_TRACK";

        Integer monthsInProgram = startedAtTs != null
                ? Math.max(0, (int) java.time.temporal.ChronoUnit.MONTHS.between(
                        startedAtTs.toInstant().atZone(ZoneOffset.UTC).toLocalDate(),
                        today))
                : null;

        return new ManagerDtos.ActiveInternRow(
                lifecycleId, userId,
                rs.getString("full_name"), rs.getString("email"),
                rs.getString("employee_id"),
                rs.getString("technology"),
                rs.getString("work_auth_type"),
                health,
                project, meeting, evaluation, timesheet,
                nullableUuid(rs.getString("manager_id")), rs.getString("manager_name"),
                nullableUuid(rs.getString("erm_id")), rs.getString("erm_owner_name"),
                nullableUuid(rs.getString("trainer_id")), rs.getString("trainer_name"),
                nullableUuid(rs.getString("evaluator_id")), rs.getString("evaluator_name"),
                startedAtTs != null ? startedAtTs.toInstant() : null,
                monthsInProgram);
    }

    private long safeCount(String sql) {
        try {
            Long v = jdbc.queryForObject(sql, Long.class);
            return v != null ? v : 0L;
        } catch (Exception e) {
            log.warn("[ManagerActiveInterns] count failed: {}", e.getMessage());
            return 0L;
        }
    }

    private static UUID nullableUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }
}
