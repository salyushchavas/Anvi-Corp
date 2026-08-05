package com.anvicorp.api.trainer.reports;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.erm.exception.ExceptionType;
import com.anvicorp.api.erm.reports.CsvExporter;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.trainer.reports.TrainerReportsDtos.FilterOptions;
import com.anvicorp.api.trainer.reports.TrainerReportsDtos.HeadlineStats;
import com.anvicorp.api.trainer.reports.TrainerReportsDtos.InternOption;
import com.anvicorp.api.trainer.reports.TrainerReportsDtos.InternRollup;
import com.anvicorp.api.trainer.reports.TrainerReportsDtos.MeetingBucket;
import com.anvicorp.api.trainer.reports.TrainerReportsDtos.MonthlyProgressReport;
import com.anvicorp.api.trainer.reports.TrainerReportsDtos.StatusBucket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Trainer Phase 4 — Monthly Progress Report aggregates + CSV stream.
 *  Mirrors the ERM Phase 7 reports pattern (JdbcTemplate + raw SQL,
 *  StreamingResponseBody + UTF-8 BOM + RFC 4180 CSV via
 *  {@link com.anvicorp.api.erm.reports.CsvExporter}). */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrainerReportsService {

    private static final int CSV_MAX_ROWS = 1_000;

    private final JdbcTemplate jdbc;

    // ── JSON report ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public MonthlyProgressReport getMonthlyProgressReport(String monthYear,
                                                            User caller) {
        requireTrainer(caller);
        YearMonth ym = parseMonthYear(monthYear);

        Instant from = ym.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = ym.plusMonths(1).atDay(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        String ymStr = ym.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        boolean superAdmin = caller.getRoles().contains(UserRole.SUPER_ADMIN);
        UUID trainerId = caller.getId();

        HeadlineStats headline = computeHeadline(ymStr, from, to, trainerId, superAdmin);
        List<StatusBucket> projectStatus = new ArrayList<>(List.of(
                new StatusBucket("Assigned", headline.projectsAssigned()),
                new StatusBucket("Completed", headline.projectsCompleted()),
                new StatusBucket("In revision", headline.projectsInRevision()),
                new StatusBucket("Escalated", headline.projectsEscalated())));
        List<MeetingBucket> meetingAttendance = new ArrayList<>(List.of(
                new MeetingBucket("Scheduled", headline.weeklyMeetingsScheduled()),
                new MeetingBucket("Completed", headline.weeklyMeetingsCompleted()),
                new MeetingBucket("Missed", headline.weeklyMeetingsMissed()),
                new MeetingBucket("Cancelled", headline.weeklyMeetingsCancelled())));
        List<InternRollup> rollups = computeInternRollups(ymStr, from, to,
                trainerId, superAdmin);
        return new MonthlyProgressReport(headline, projectStatus,
                meetingAttendance, rollups);
    }

    // ── CSV stream ────────────────────────────────────────────────────────

    public void exportCsv(String monthYear, User caller, OutputStream out)
            throws IOException {
        requireTrainer(caller);
        MonthlyProgressReport report = getMonthlyProgressReport(monthYear, caller);
        CsvExporter.writeBom(out);
        CsvExporter.writeRow(out, List.of(
                "intern_name", "employee_id", "technology_area",
                "projects_assigned_count", "projects_completed_count",
                "projects_in_revision_count", "projects_escalated_count",
                "weekly_meetings_scheduled", "weekly_meetings_completed",
                "weekly_meetings_missed", "pending_review_count",
                "average_review_turnaround_hours", "latest_review_date"));
        int written = 0;
        for (InternRollup r : report.internRollups()) {
            if (written >= CSV_MAX_ROWS) break;
            CsvExporter.writeRow(out, List.of(
                    nz(r.internName()), nz(r.employeeId()), nz(r.technologyArea()),
                    r.projectsAssignedCount(), r.projectsCompletedCount(),
                    r.projectsInRevisionCount(), r.projectsEscalatedCount(),
                    r.weeklyMeetingsScheduled(), r.weeklyMeetingsCompleted(),
                    r.weeklyMeetingsMissed(), r.pendingReviewCount(),
                    r.averageReviewTurnaroundHours() == null ? ""
                            : String.format("%.2f", r.averageReviewTurnaroundHours()),
                    r.latestReviewDate() == null ? "" : r.latestReviewDate().toString()));
            written++;
        }
    }

    // ── Filter options ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public FilterOptions getFilterOptions(User caller) {
        requireTrainer(caller);
        boolean superAdmin = caller.getRoles().contains(UserRole.SUPER_ADMIN);

        // Distinct month_year values where this trainer has at least one
        // project. Limited to the past 24 months so the dropdown stays
        // tractable even after a couple of years of usage.
        StringBuilder sql = new StringBuilder()
                .append("SELECT DISTINCT p.month_year FROM projects p ")
                .append("  JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id ")
                .append(" WHERE p.month_year IS NOT NULL ");
        List<Object> params = new ArrayList<>();
        if (!superAdmin) {
            sql.append(" AND il.trainer_id = ? ");
            params.add(caller.getId());
        }
        sql.append(" ORDER BY p.month_year DESC LIMIT 24");
        List<String> months = new ArrayList<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(sql.toString(),
                    params.toArray())) {
                months.add((String) r.get("month_year"));
            }
        } catch (Exception e) {
            log.warn("[TrainerReports] month_year filter query failed: {}",
                    e.getMessage());
        }
        // Always include the current month so the page can render even
        // before the trainer has any assigned projects.
        String current = YearMonth.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM"));
        if (!months.contains(current)) months.add(0, current);

        StringBuilder isql = new StringBuilder()
                .append("SELECT il.id, u.full_name, il.employee_id FROM intern_lifecycles il ")
                .append("  JOIN users u ON u.id = il.user_id ");
        List<Object> ip = new ArrayList<>();
        if (!superAdmin) {
            isql.append(" WHERE il.trainer_id = ? ");
            ip.add(caller.getId());
        }
        isql.append(" ORDER BY u.full_name ASC LIMIT 200");
        List<InternOption> interns = new ArrayList<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(isql.toString(),
                    ip.toArray())) {
                interns.add(new InternOption(
                        toUuid(r.get("id")),
                        (String) r.get("full_name"),
                        (String) r.get("employee_id")));
            }
        } catch (Exception e) {
            log.warn("[TrainerReports] intern filter query failed: {}",
                    e.getMessage());
        }
        return new FilterOptions(months, interns);
    }

    // ── Aggregations ──────────────────────────────────────────────────────

    private HeadlineStats computeHeadline(String monthYear, Instant from, Instant to,
                                           UUID trainerId, boolean superAdmin) {
        String trainerClause = superAdmin ? "" : " AND il.trainer_id = ? ";
        List<Object> trainerParam = superAdmin ? List.of() : List.of(trainerId);

        int activeInterns = (int) safeCount(
                "SELECT COUNT(DISTINCT il.id) FROM intern_lifecycles il "
                        + " WHERE il.active_status IN ('PROSPECTIVE','ACTIVE') "
                        + trainerClause,
                trainerParam.toArray());
        int projectsAssigned = (int) safeCount(
                "SELECT COUNT(*) FROM projects p "
                        + "  JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                        + " WHERE p.month_year = ? " + trainerClause,
                paramsWithFirst(monthYear, trainerParam));
        int projectsCompleted = (int) safeCount(
                "SELECT COUNT(*) FROM projects p "
                        + "  JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                        + " WHERE p.month_year = ? AND p.status = 'COMPLETED' "
                        + trainerClause,
                paramsWithFirst(monthYear, trainerParam));
        int projectsInRevision = (int) safeCount(
                "SELECT COUNT(*) FROM projects p "
                        + "  JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                        + " WHERE p.month_year = ? AND p.status = 'RETURNED' "
                        + trainerClause,
                paramsWithFirst(monthYear, trainerParam));
        int projectsEscalated = (int) safeCount(
                "SELECT COUNT(DISTINCT er.id) FROM exception_records er "
                        + "  JOIN intern_lifecycles il ON il.id = er.intern_lifecycle_id "
                        + " WHERE er.exception_type = ? "
                        + "   AND er.opened_at >= ? AND er.opened_at < ? "
                        + trainerClause,
                paramsArrayPrepend(
                        ExceptionType.TRAINER_ESCALATION.name(),
                        java.sql.Timestamp.from(from),
                        java.sql.Timestamp.from(to),
                        trainerParam));

        int meetingsScheduled = (int) safeCount(
                "SELECT COUNT(*) FROM weekly_meetings wm "
                        + "  JOIN intern_lifecycles il ON il.id = wm.intern_lifecycle_id "
                        + " WHERE wm.scheduled_for >= ? AND wm.scheduled_for < ? "
                        + trainerClause,
                paramsArrayPrepend(
                        java.sql.Timestamp.from(from),
                        java.sql.Timestamp.from(to),
                        trainerParam));
        int meetingsCompleted = (int) safeCount(
                "SELECT COUNT(*) FROM weekly_meetings wm "
                        + "  JOIN intern_lifecycles il ON il.id = wm.intern_lifecycle_id "
                        + " WHERE wm.scheduled_for >= ? AND wm.scheduled_for < ? "
                        + "   AND wm.status = 'COMPLETED' "
                        + trainerClause,
                paramsArrayPrepend(
                        java.sql.Timestamp.from(from),
                        java.sql.Timestamp.from(to),
                        trainerParam));
        int meetingsMissed = (int) safeCount(
                "SELECT COUNT(*) FROM weekly_meetings wm "
                        + "  JOIN intern_lifecycles il ON il.id = wm.intern_lifecycle_id "
                        + " WHERE wm.scheduled_for >= ? AND wm.scheduled_for < ? "
                        + "   AND wm.status = 'NO_SHOW' "
                        + trainerClause,
                paramsArrayPrepend(
                        java.sql.Timestamp.from(from),
                        java.sql.Timestamp.from(to),
                        trainerParam));
        int meetingsCancelled = (int) safeCount(
                "SELECT COUNT(*) FROM weekly_meetings wm "
                        + "  JOIN intern_lifecycles il ON il.id = wm.intern_lifecycle_id "
                        + " WHERE wm.scheduled_for >= ? AND wm.scheduled_for < ? "
                        + "   AND wm.status = 'CANCELLED' "
                        + trainerClause,
                paramsArrayPrepend(
                        java.sql.Timestamp.from(from),
                        java.sql.Timestamp.from(to),
                        trainerParam));

        // Window on submitted_at so a past-month report shows the
        // pending-review count that BELONGED to that month, not today's
        // live "still pending right now" number. Current-month reports
        // remain equivalent because from/to = this month's boundaries and
        // any submission not yet reviewed still falls inside them.
        int pendingBacklog = (int) safeCount(
                "SELECT COUNT(*) FROM project_submissions s "
                        + "  JOIN projects p ON p.id = s.project_id "
                        + "  JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                        + " WHERE s.trainer_decision IS NULL "
                        + "   AND s.submitted_at >= ? AND s.submitted_at < ? "
                        + trainerClause,
                paramsArrayPrepend(
                        java.sql.Timestamp.from(from),
                        java.sql.Timestamp.from(to),
                        trainerParam));

        Double avgHours = null;
        try {
            Double v = jdbc.queryForObject(
                    "SELECT AVG(EXTRACT(EPOCH FROM (s.reviewed_at - s.submitted_at))/3600) "
                            + "  FROM project_submissions s "
                            + "  JOIN projects p ON p.id = s.project_id "
                            + "  JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                            + " WHERE s.reviewed_at IS NOT NULL "
                            + "   AND s.reviewed_at >= ? AND s.reviewed_at < ? "
                            + trainerClause,
                    Double.class,
                    paramsArrayPrepend(
                            java.sql.Timestamp.from(from),
                            java.sql.Timestamp.from(to),
                            trainerParam));
            avgHours = v;
        } catch (Exception e) {
            log.debug("[TrainerReports] avg turnaround failed: {}", e.getMessage());
        }
        return new HeadlineStats(monthYear, activeInterns,
                projectsAssigned, projectsCompleted, projectsInRevision,
                projectsEscalated, meetingsScheduled, meetingsCompleted,
                meetingsMissed, meetingsCancelled, pendingBacklog, avgHours);
    }

    private List<InternRollup> computeInternRollups(String monthYear,
                                                     Instant from, Instant to,
                                                     UUID trainerId,
                                                     boolean superAdmin) {
        String trainerClause = superAdmin ? "" : " AND il.trainer_id = ? ";
        StringBuilder sql = new StringBuilder()
                .append("SELECT il.id AS lc_id, il.user_id AS intern_user_id, ")
                .append("       u.full_name AS intern_name, il.employee_id, ")
                .append("       MAX(p.tech_stack) AS technology_area ")
                .append("  FROM intern_lifecycles il ")
                .append("  JOIN users u ON u.id = il.user_id ")
                .append("  LEFT JOIN projects p ON p.intern_lifecycle_id = il.id "
                        + "AND p.month_year = ? ")
                .append(" WHERE 1=1 ").append(trainerClause)
                .append(" GROUP BY il.id, il.user_id, u.full_name, il.employee_id ")
                .append(" ORDER BY u.full_name ASC LIMIT 1000");
        List<Object> params = new ArrayList<>();
        params.add(monthYear);
        if (!superAdmin) params.add(trainerId);

        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(sql.toString(), params.toArray());
        } catch (Exception e) {
            log.warn("[TrainerReports] roll-up base query failed: {}",
                    e.getMessage());
            return List.of();
        }
        List<InternRollup> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            UUID lcId = toUuid(r.get("lc_id"));
            UUID internUserId = toUuid(r.get("intern_user_id"));
            String internName = (String) r.get("intern_name");
            String employeeId = (String) r.get("employee_id");
            String tech = (String) r.get("technology_area");
            int projectsAssigned = (int) safeCount(
                    "SELECT COUNT(*) FROM projects p "
                            + " WHERE p.intern_lifecycle_id = ? AND p.month_year = ?",
                    lcId, monthYear);
            int projectsCompleted = (int) safeCount(
                    "SELECT COUNT(*) FROM projects p "
                            + " WHERE p.intern_lifecycle_id = ? AND p.month_year = ? "
                            + "   AND p.status = 'COMPLETED'",
                    lcId, monthYear);
            int projectsInRevision = (int) safeCount(
                    "SELECT COUNT(*) FROM projects p "
                            + " WHERE p.intern_lifecycle_id = ? AND p.month_year = ? "
                            + "   AND p.status = 'RETURNED'",
                    lcId, monthYear);
            int projectsEscalated = (int) safeCount(
                    "SELECT COUNT(*) FROM exception_records er "
                            + " WHERE er.intern_lifecycle_id = ? "
                            + "   AND er.exception_type = ? "
                            + "   AND er.opened_at >= ? AND er.opened_at < ?",
                    lcId, ExceptionType.TRAINER_ESCALATION.name(),
                    java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
            int meetingsScheduled = (int) safeCount(
                    "SELECT COUNT(*) FROM weekly_meetings wm "
                            + " WHERE wm.intern_lifecycle_id = ? "
                            + "   AND wm.scheduled_for >= ? AND wm.scheduled_for < ?",
                    lcId, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
            int meetingsCompleted = (int) safeCount(
                    "SELECT COUNT(*) FROM weekly_meetings wm "
                            + " WHERE wm.intern_lifecycle_id = ? "
                            + "   AND wm.scheduled_for >= ? AND wm.scheduled_for < ? "
                            + "   AND wm.status = 'COMPLETED'",
                    lcId, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
            int meetingsMissed = (int) safeCount(
                    "SELECT COUNT(*) FROM weekly_meetings wm "
                            + " WHERE wm.intern_lifecycle_id = ? "
                            + "   AND wm.scheduled_for >= ? AND wm.scheduled_for < ? "
                            + "   AND wm.status = 'NO_SHOW'",
                    lcId, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
            // Per-intern pending count also windows on submitted_at —
            // otherwise a historical report shows today's live pending
            // count against the past-month rollup.
            int pendingReview = (int) safeCount(
                    "SELECT COUNT(*) FROM project_submissions s "
                            + "  JOIN projects p ON p.id = s.project_id "
                            + " WHERE p.intern_lifecycle_id = ? "
                            + "   AND s.trainer_decision IS NULL "
                            + "   AND s.submitted_at >= ? AND s.submitted_at < ?",
                    lcId,
                    java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
            Double avgHours = null;
            try {
                avgHours = jdbc.queryForObject(
                        "SELECT AVG(EXTRACT(EPOCH FROM (s.reviewed_at - s.submitted_at))/3600) "
                                + "  FROM project_submissions s "
                                + "  JOIN projects p ON p.id = s.project_id "
                                + " WHERE p.intern_lifecycle_id = ? "
                                + "   AND s.reviewed_at >= ? AND s.reviewed_at < ? "
                                + "   AND s.reviewed_at IS NOT NULL",
                        Double.class, lcId,
                        java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
            } catch (Exception ignored) {}
            Instant latest = null;
            try {
                java.sql.Timestamp ts = jdbc.queryForObject(
                        "SELECT MAX(s.reviewed_at) FROM project_submissions s "
                                + "  JOIN projects p ON p.id = s.project_id "
                                + " WHERE p.intern_lifecycle_id = ?",
                        java.sql.Timestamp.class, lcId);
                if (ts != null) latest = ts.toInstant();
            } catch (Exception ignored) {}
            out.add(new InternRollup(
                    lcId, internUserId, internName, employeeId, tech,
                    projectsAssigned, projectsCompleted,
                    projectsInRevision, projectsEscalated,
                    meetingsScheduled, meetingsCompleted, meetingsMissed,
                    pendingReview, avgHours, latest));
        }
        return out;
    }

    // ── Primitive helpers ────────────────────────────────────────────────

    private void requireTrainer(User caller) {
        if (caller == null) throw new ForbiddenException("Caller required");
        if (!caller.getRoles().contains(UserRole.TRAINER)
                && !caller.getRoles().contains(UserRole.SUPER_ADMIN)) {
            throw new ForbiddenException("TRAINER or SUPER_ADMIN required");
        }
    }

    private YearMonth parseMonthYear(String monthYear) {
        if (monthYear == null || monthYear.isBlank()) return YearMonth.now();
        try {
            return YearMonth.parse(monthYear,
                    DateTimeFormatter.ofPattern("yyyy-MM"));
        } catch (Exception e) {
            throw new BadRequestException("monthYear must be YYYY-MM");
        }
    }

    private long safeCount(String sql, Object... params) {
        try {
            Long v = jdbc.queryForObject(sql, Long.class, params);
            return v == null ? 0L : v;
        } catch (Exception e) {
            log.debug("[TrainerReports] count failed: {}", e.getMessage());
            return 0L;
        }
    }

    private Object[] paramsWithFirst(Object first, List<Object> rest) {
        Object[] arr = new Object[1 + rest.size()];
        arr[0] = first;
        for (int i = 0; i < rest.size(); i++) arr[i + 1] = rest.get(i);
        return arr;
    }

    private Object[] paramsArrayPrepend(Object first, Object second, List<Object> rest) {
        Object[] arr = new Object[2 + rest.size()];
        arr[0] = first;
        arr[1] = second;
        for (int i = 0; i < rest.size(); i++) arr[i + 2] = rest.get(i);
        return arr;
    }

    private Object[] paramsArrayPrepend(Object first, Object second, Object third,
                                         List<Object> rest) {
        Object[] arr = new Object[3 + rest.size()];
        arr[0] = first;
        arr[1] = second;
        arr[2] = third;
        for (int i = 0; i < rest.size(); i++) arr[i + 3] = rest.get(i);
        return arr;
    }

    private static UUID toUuid(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        try { return UUID.fromString(String.valueOf(o)); }
        catch (Exception e) { return null; }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
