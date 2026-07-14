package com.anvicorp.api.manager;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Manager Phase 3B — read side of the timesheet approval queue.
 * Portfolio-wide VIEW for MANAGER + SUPER_ADMIN; per-row hour / detail
 * visibility is gated by ownership (managerId == caller.id) so a manager
 * never sees the hours / per-day breakdown of a timesheet they can't
 * act on. SUPER_ADMIN sees everything.
 *
 * <p>Action endpoints live in {@link ManagerTimesheetApprovalService};
 * the actual SUBMITTED → APPROVED / REJECTED transition happens via the
 * existing {@code TimesheetService} so the platform's state machine,
 * approver-id wiring, and (future) audit live in one place.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerTimesheetService {

    private final JdbcTemplate jdbc;

    public ManagerDtos.TimesheetListResponse list(
            User caller,
            String status,        // SUBMITTED (default) | APPROVED | REJECTED | DRAFT | ALL
            UUID managerId,       // "My interns" filter (caller.id when toggled)
            UUID ermOwner,
            String technology,
            String weekStart,     // yyyy-MM-dd
            String search,
            int page,
            int pageSize) {

        if (caller == null || caller.getRoles() == null
                || (!caller.getRoles().contains(UserRole.MANAGER)
                    && !caller.getRoles().contains(UserRole.SUPER_ADMIN))) {
            throw new SecurityException("Manager or SUPER_ADMIN required");
        }
        boolean superAdmin = caller.getRoles().contains(UserRole.SUPER_ADMIN);

        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, pageSize), 100);
        int offset = safePage * safeSize;

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (status == null || status.isBlank()) {
            where.append(" AND ts.status = 'SUBMITTED' ");
        } else if (!"ALL".equalsIgnoreCase(status)) {
            where.append(" AND ts.status = ? ");
            params.add(status.toUpperCase());
        }
        if (managerId != null) {
            where.append(" AND il.manager_id = ? ");
            params.add(managerId);
        }
        if (ermOwner != null) {
            where.append(" AND il.erm_id = ? ");
            params.add(ermOwner);
        }
        if (technology != null && !technology.isBlank()) {
            where.append(" AND EXISTS (")
                    .append("    SELECT 1 FROM applications a ")
                    .append("    JOIN candidates c2 ON c2.id = a.candidate_id ")
                    .append("    JOIN job_postings jp ON jp.id = a.job_posting_id ")
                    .append("    WHERE c2.user_id = u.id ")
                    .append("      AND LOWER(COALESCE(jp.technology,'')) = LOWER(?)) ");
            params.add(technology);
        }
        if (weekStart != null && !weekStart.isBlank()) {
            try {
                LocalDate wk = LocalDate.parse(weekStart);
                where.append(" AND ts.week_start = ? ");
                params.add(wk);
            } catch (Exception ignored) {
                // bad date — silently ignore so the queue still renders
            }
        }
        if (search != null && !search.isBlank()) {
            String like = "%" + search.trim().toLowerCase() + "%";
            where.append(" AND (LOWER(u.full_name) LIKE ? "
                    + " OR LOWER(u.email) LIKE ? "
                    + " OR LOWER(COALESCE(il.employee_id,'')) LIKE ?) ");
            params.add(like); params.add(like); params.add(like);
        }

        String fromAndJoins = ""
                + "  FROM timesheets ts "
                + "  JOIN candidates c ON c.id = ts.intern_id "
                + "  JOIN users u ON u.id = c.user_id "
                + "  LEFT JOIN intern_lifecycles il ON il.user_id = u.id ";

        long total = 0L;
        try {
            Long v = jdbc.queryForObject(
                    "SELECT COUNT(*) " + fromAndJoins + where,
                    Long.class, params.toArray());
            total = v != null ? v : 0L;
        } catch (Exception e) {
            log.warn("[ManagerTimesheet] count failed: {}", e.getMessage());
        }

        String sql = ""
                + "SELECT ts.id AS ts_id, ts.week_start, ts.status, ts.hours, "
                + "       ts.description, ts.review_note, "
                + "       ts.approved_by, ts.approved_at, "
                + "       (SELECT au.full_name FROM users au WHERE au.id = ts.approved_by) AS approver_name, "
                + "       u.id AS user_id, u.full_name AS intern_name, "
                + "       il.employee_id, il.manager_id, il.erm_id, "
                + "       (SELECT mu.full_name FROM users mu WHERE mu.id = il.manager_id) AS manager_name, "
                + "       (SELECT eu.full_name FROM users eu WHERE eu.id = il.erm_id) AS erm_owner_name, "
                + "       (SELECT jp.technology FROM applications a "
                + "          JOIN candidates c2 ON c2.id = a.candidate_id "
                + "          JOIN job_postings jp ON jp.id = a.job_posting_id "
                + "         WHERE c2.user_id = u.id "
                + "         ORDER BY a.applied_at DESC LIMIT 1) AS technology "
                + fromAndJoins
                + where
                + " ORDER BY ts.week_start DESC, ts.created_at DESC "
                + " LIMIT " + safeSize + " OFFSET " + offset;

        List<ManagerDtos.TimesheetRow> rows = new ArrayList<>();
        try {
            rows = jdbc.query(sql, params.toArray(),
                    (rs, n) -> mapRow(rs, caller, superAdmin));
        } catch (Exception e) {
            log.warn("[ManagerTimesheet] list failed: {}", e.getMessage());
        }

        int totalPages = safeSize == 0 ? 0 : (int) Math.ceil(total / (double) safeSize);
        return new ManagerDtos.TimesheetListResponse(
                rows, safePage, safeSize, total, totalPages);
    }

    public ManagerDtos.TimesheetFilterOptions filterOptions() {
        List<String> statuses = List.of("DRAFT", "SUBMITTED", "APPROVED", "REJECTED");
        List<String> technologies = new ArrayList<>();
        try {
            jdbc.query(
                    "SELECT DISTINCT technology FROM job_postings "
                            + " WHERE technology IS NOT NULL AND technology <> '' "
                            + " ORDER BY technology",
                    rs -> { technologies.add(rs.getString(1)); });
        } catch (Exception e) {
            log.warn("[ManagerTimesheet] technology distinct failed: {}", e.getMessage());
        }
        List<ManagerDtos.UserOption> managers = new ArrayList<>();
        try {
            jdbc.query(
                    "SELECT DISTINCT u.id, u.full_name "
                            + "  FROM intern_lifecycles il "
                            + "  JOIN users u ON u.id = il.manager_id "
                            + " WHERE il.manager_id IS NOT NULL "
                            + " ORDER BY u.full_name",
                    rs -> {
                        managers.add(new ManagerDtos.UserOption(
                                UUID.fromString(rs.getString("id")),
                                rs.getString("full_name")));
                    });
        } catch (Exception e) {
            log.warn("[ManagerTimesheet] managers distinct failed: {}", e.getMessage());
        }
        List<ManagerDtos.ErmOwnerOption> owners = new ArrayList<>();
        try {
            jdbc.query(
                    "SELECT DISTINCT u.id, u.full_name "
                            + "  FROM intern_lifecycles il "
                            + "  JOIN users u ON u.id = il.erm_id "
                            + " WHERE il.erm_id IS NOT NULL "
                            + " ORDER BY u.full_name",
                    rs -> {
                        owners.add(new ManagerDtos.ErmOwnerOption(
                                UUID.fromString(rs.getString("id")),
                                rs.getString("full_name")));
                    });
        } catch (Exception e) {
            log.warn("[ManagerTimesheet] owners distinct failed: {}", e.getMessage());
        }
        return new ManagerDtos.TimesheetFilterOptions(statuses, technologies, managers, owners);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private ManagerDtos.TimesheetRow mapRow(
            java.sql.ResultSet rs, User caller, boolean superAdmin)
            throws java.sql.SQLException {
        UUID tsId = UUID.fromString(rs.getString("ts_id"));
        UUID managerId = nullableUuid(rs.getString("manager_id"));
        boolean canAct = superAdmin
                || (managerId != null && managerId.equals(caller.getId()));

        // Hours / description / per-day rows ONLY when canAct. Otherwise
        // the manager sees status + approver + week + intern only — they
        // can verify ERM approved it without seeing the hours.
        BigDecimal hours = canAct ? rs.getBigDecimal("hours") : null;
        String description = canAct ? rs.getString("description") : null;
        List<ManagerDtos.TimesheetDayBreakdown> days = canAct
                ? loadDayBreakdown(tsId) : null;

        return new ManagerDtos.TimesheetRow(
                tsId,
                UUID.fromString(rs.getString("user_id")),
                rs.getString("intern_name"),
                rs.getString("employee_id"),
                rs.getString("technology"),
                managerId, rs.getString("manager_name"),
                nullableUuid(rs.getString("erm_id")), rs.getString("erm_owner_name"),
                rs.getDate("week_start") != null
                        ? rs.getDate("week_start").toLocalDate() : null,
                rs.getString("status"),
                nullableUuid(rs.getString("approved_by")),
                rs.getString("approver_name"),
                rs.getTimestamp("approved_at") != null
                        ? rs.getTimestamp("approved_at").toInstant() : null,
                canAct,
                hours,
                description,
                days,
                rs.getString("review_note"));
    }

    private List<ManagerDtos.TimesheetDayBreakdown> loadDayBreakdown(UUID timesheetId) {
        List<ManagerDtos.TimesheetDayBreakdown> out = new ArrayList<>();
        try {
            jdbc.query(
                    "SELECT day_of_week, hours, notes "
                            + "  FROM timesheet_days "
                            + " WHERE timesheet_id = ? "
                            + " ORDER BY CASE day_of_week "
                            + "   WHEN 'MONDAY' THEN 1 WHEN 'TUESDAY' THEN 2 "
                            + "   WHEN 'WEDNESDAY' THEN 3 WHEN 'THURSDAY' THEN 4 "
                            + "   WHEN 'FRIDAY' THEN 5 WHEN 'SATURDAY' THEN 6 "
                            + "   WHEN 'SUNDAY' THEN 7 ELSE 8 END",
                    rs -> {
                        out.add(new ManagerDtos.TimesheetDayBreakdown(
                                rs.getString("day_of_week"),
                                rs.getBigDecimal("hours"),
                                rs.getString("notes")));
                    },
                    timesheetId);
        } catch (Exception e) {
            log.warn("[ManagerTimesheet] day breakdown failed for {}: {}",
                    timesheetId, e.getMessage());
        }
        return out;
    }

    private static UUID nullableUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }
}
