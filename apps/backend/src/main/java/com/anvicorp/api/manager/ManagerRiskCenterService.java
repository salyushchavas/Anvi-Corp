package com.anvicorp.api.manager;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.erm.escalation.ErmEscalationDtos;
import com.anvicorp.api.erm.escalation.ErmEscalationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Manager Phase 4A — Risk Center read service + write delegate.
 *
 * <p>READS: queries {@code exception_records} directly (the same table
 * {@code ExceptionScanJob} maintains every 15 min via the same detectors
 * + thresholds Manager Phase 3A uses on-demand). Counts reconcile with
 * the ERM Escalations dashboard and the Phase 3A at-risk set ± the
 * 15-minute scan cadence — the intentional drift between "current
 * snapshot" (3A) and "tracked, assignable items" (Risk Center).</p>
 *
 * <p>WRITES: thin delegate to {@link ErmEscalationService}. Same state
 * machine, audit log, and {@code exception_event_logs} entries — no
 * parallel escalation engine. Action endpoints are portfolio-wide per
 * the spec ("any MANAGER and SUPER_ADMIN can escalate/assign on any
 * exception"); only the role gate (@PreAuthorize) is enforced — no
 * service-layer ownership check.</p>
 *
 * <p>Masking: the persisted ExceptionRecord schema already stores only
 * status, severity, type, and a sanitised {@code payloadJson}
 * (e.g. {@code {"daysOverdue":12}}). ERM-only fields like
 * {@code resolution_note} are scrubbed by the existing
 * {@link ErmEscalationDtos.ExceptionRow} record shape — Manager
 * reuses that exact field set in {@link ManagerDtos.RiskRow} so no
 * additional masking work is required.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerRiskCenterService {

    /** Open + actionable statuses — the default "what's in flight". */
    private static final List<String> OPEN_STATUSES =
            List.of("OPEN", "ASSIGNED", "IN_PROGRESS");

    private final JdbcTemplate jdbc;
    private final ErmEscalationService ermEscalationService;

    // ── Reads ────────────────────────────────────────────────────────────

    public ManagerDtos.RiskListResponse list(
            User caller,
            List<String> severity,
            List<String> exceptionType,
            List<String> status,        // null/empty → OPEN_STATUSES
            UUID assignedToId,
            UUID managerId,             // "My interns" — sets to caller.id when toggled
            String search,
            int page, int pageSize) {

        requireManagerOrSuperAdmin(caller);

        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, pageSize), 100);
        int offset = safePage * safeSize;

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (status == null || status.isEmpty()) {
            where.append(" AND er.status IN (?,?,?) ");
            params.addAll(OPEN_STATUSES);
        } else {
            where.append(" AND er.status IN (")
                    .append(placeholders(status.size())).append(") ");
            params.addAll(status);
        }
        if (severity != null && !severity.isEmpty()) {
            where.append(" AND er.severity IN (")
                    .append(placeholders(severity.size())).append(") ");
            params.addAll(severity);
        }
        if (exceptionType != null && !exceptionType.isEmpty()) {
            where.append(" AND er.exception_type IN (")
                    .append(placeholders(exceptionType.size())).append(") ");
            params.addAll(exceptionType);
        }
        if (assignedToId != null) {
            where.append(" AND er.assigned_to_id = ? ");
            params.add(assignedToId);
        }
        if (managerId != null) {
            where.append(" AND il.manager_id = ? ");
            params.add(managerId);
        }
        if (search != null && !search.isBlank()) {
            String like = "%" + search.trim().toLowerCase() + "%";
            where.append(" AND (LOWER(COALESCE(u.full_name,'')) LIKE ? "
                    + " OR LOWER(COALESCE(u.email,'')) LIKE ? "
                    + " OR LOWER(COALESCE(il.employee_id,'')) LIKE ?) ");
            params.add(like); params.add(like); params.add(like);
        }

        String fromAndJoins = ""
                + "  FROM exception_records er "
                + "  JOIN intern_lifecycles il ON il.id = er.intern_lifecycle_id "
                + "  LEFT JOIN users u ON u.id = er.subject_user_id ";

        long total = 0L;
        try {
            Long v = jdbc.queryForObject(
                    "SELECT COUNT(*) " + fromAndJoins + where,
                    Long.class, params.toArray());
            total = v != null ? v : 0L;
        } catch (Exception e) {
            log.warn("[ManagerRisk] count failed: {}", e.getMessage());
        }

        String sql = ""
                + "SELECT er.id, er.exception_type, er.severity, er.status, "
                + "       er.subject_user_id, er.intern_lifecycle_id, "
                + "       er.assigned_to_id, "
                + "       (SELECT a.full_name FROM users a WHERE a.id = er.assigned_to_id) AS assigned_to_name, "
                + "       er.opened_at, er.last_seen_at, er.assigned_at, er.resolved_at, "
                + "       er.subject_resource_type, er.subject_resource_id, er.payload_json, "
                + "       u.full_name AS subject_name, "
                + "       il.employee_id, il.manager_id, il.erm_id, "
                + "       (SELECT m.full_name FROM users m WHERE m.id = il.manager_id) AS manager_name, "
                + "       (SELECT e.full_name FROM users e WHERE e.id = il.erm_id) AS erm_owner_name "
                + fromAndJoins
                + where
                + " ORDER BY CASE er.severity "
                + "            WHEN 'URGENT' THEN 0 "
                + "            WHEN 'WARN' THEN 1 "
                + "            ELSE 2 END, "
                + "          er.opened_at ASC "
                + " LIMIT " + safeSize + " OFFSET " + offset;

        Instant now = Instant.now();
        List<ManagerDtos.RiskRow> rows = new ArrayList<>();
        try {
            rows = jdbc.query(sql, params.toArray(), (rs, n) -> mapRow(rs, now));
        } catch (Exception e) {
            log.warn("[ManagerRisk] list failed: {}", e.getMessage());
        }

        int totalPages = safeSize == 0 ? 0 : (int) Math.ceil(total / (double) safeSize);
        return new ManagerDtos.RiskListResponse(
                rows, safePage, safeSize, total, totalPages);
    }

    public ManagerDtos.RiskSummary summary(User caller) {
        requireManagerOrSuperAdmin(caller);

        long urgent = safeCount(
                "SELECT COUNT(*) FROM exception_records "
                        + " WHERE status IN ('OPEN','ASSIGNED','IN_PROGRESS') "
                        + "   AND severity = 'URGENT'");
        long warn = safeCount(
                "SELECT COUNT(*) FROM exception_records "
                        + " WHERE status IN ('OPEN','ASSIGNED','IN_PROGRESS') "
                        + "   AND severity = 'WARN'");
        long info = safeCount(
                "SELECT COUNT(*) FROM exception_records "
                        + " WHERE status IN ('OPEN','ASSIGNED','IN_PROGRESS') "
                        + "   AND severity = 'INFO'");
        long assigned = safeCount(
                "SELECT COUNT(*) FROM exception_records "
                        + " WHERE status IN ('ASSIGNED','IN_PROGRESS')");
        long resolved30 = safeCount(
                "SELECT COUNT(*) FROM exception_records "
                        + " WHERE status IN ('RESOLVED','AUTO_RESOLVED') "
                        + "   AND resolved_at >= NOW() - INTERVAL '30 days'");

        List<ManagerDtos.RiskTypeCount> byType = new ArrayList<>();
        try {
            jdbc.query(
                    "SELECT exception_type, severity, COUNT(*) AS n "
                            + "  FROM exception_records "
                            + " WHERE status IN ('OPEN','ASSIGNED','IN_PROGRESS') "
                            + " GROUP BY exception_type, severity "
                            + " ORDER BY "
                            + "   CASE severity WHEN 'URGENT' THEN 0 WHEN 'WARN' THEN 1 ELSE 2 END, "
                            + "   n DESC "
                            + " LIMIT 5",
                    rs -> {
                        byType.add(new ManagerDtos.RiskTypeCount(
                                rs.getString("exception_type"),
                                rs.getString("severity"),
                                rs.getLong("n")));
                    });
        } catch (Exception e) {
            log.warn("[ManagerRisk] byType failed: {}", e.getMessage());
        }

        return new ManagerDtos.RiskSummary(
                urgent + warn + info, urgent, warn, info,
                assigned, resolved30, byType);
    }

    public ManagerDtos.RiskFilterOptions filterOptions() {
        List<String> severities = List.of("URGENT", "WARN", "INFO");
        List<String> statuses = List.of("OPEN", "ASSIGNED", "IN_PROGRESS",
                "RESOLVED", "DISMISSED", "AUTO_RESOLVED");
        List<String> exceptionTypes = new ArrayList<>();
        try {
            jdbc.query(
                    "SELECT DISTINCT exception_type FROM exception_records "
                            + " ORDER BY exception_type",
                    rs -> { exceptionTypes.add(rs.getString(1)); });
        } catch (Exception e) {
            log.warn("[ManagerRisk] type distinct failed: {}", e.getMessage());
        }
        List<ManagerDtos.UserOption> assignees = distinctRoleAssignees(
                "SELECT DISTINCT u.id, u.full_name "
                        + "  FROM exception_records er "
                        + "  JOIN users u ON u.id = er.assigned_to_id "
                        + " WHERE er.assigned_to_id IS NOT NULL "
                        + " ORDER BY u.full_name");
        List<ManagerDtos.UserOption> managers = distinctRoleAssignees(
                "SELECT DISTINCT u.id, u.full_name "
                        + "  FROM intern_lifecycles il "
                        + "  JOIN users u ON u.id = il.manager_id "
                        + " WHERE il.manager_id IS NOT NULL "
                        + " ORDER BY u.full_name");
        return new ManagerDtos.RiskFilterOptions(
                severities, statuses, exceptionTypes, assignees, managers);
    }

    // ── Detail + write delegates to ErmEscalationService ─────────────────

    public ErmEscalationDtos.ExceptionDetail get(UUID id, User caller) {
        requireManagerOrSuperAdmin(caller);
        return ermEscalationService.get(id);
    }

    public ErmEscalationDtos.ExceptionDetail assign(
            UUID id, ManagerDtos.AssignRiskRequest req, User caller) {
        requireManagerOrSuperAdmin(caller);
        return ermEscalationService.assign(id,
                new ErmEscalationDtos.AssignRequest(req.assigneeUserId()), caller);
    }

    public ErmEscalationDtos.ExceptionDetail markInProgress(UUID id, User caller) {
        requireManagerOrSuperAdmin(caller);
        return ermEscalationService.markInProgress(id, caller);
    }

    public ErmEscalationDtos.ExceptionDetail addNote(
            UUID id, ManagerDtos.NoteRiskRequest req, User caller) {
        requireManagerOrSuperAdmin(caller);
        return ermEscalationService.addNote(id,
                new ErmEscalationDtos.NoteRequest(req.note()), caller);
    }

    public ErmEscalationDtos.ExceptionDetail resolve(
            UUID id, ManagerDtos.ResolveRiskRequest req, User caller) {
        requireManagerOrSuperAdmin(caller);
        return ermEscalationService.resolve(id,
                new ErmEscalationDtos.ResolutionRequest(
                        req.reasonCode(), req.reasonText(), req.resolutionNote()),
                caller);
    }

    public ErmEscalationDtos.ExceptionDetail reopen(UUID id, User caller) {
        requireManagerOrSuperAdmin(caller);
        return ermEscalationService.reopen(id, caller);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void requireManagerOrSuperAdmin(User caller) {
        if (caller == null || caller.getRoles() == null
                || (!caller.getRoles().contains(UserRole.MANAGER)
                    && !caller.getRoles().contains(UserRole.SUPER_ADMIN))) {
            throw new SecurityException("Manager or SUPER_ADMIN required");
        }
    }

    private ManagerDtos.RiskRow mapRow(java.sql.ResultSet rs, Instant now)
            throws java.sql.SQLException {
        Instant openedAt = rs.getTimestamp("opened_at") != null
                ? rs.getTimestamp("opened_at").toInstant() : null;
        int ageDays = openedAt != null
                ? Math.max(0, (int) ChronoUnit.DAYS.between(openedAt, now))
                : 0;
        return new ManagerDtos.RiskRow(
                UUID.fromString(rs.getString("id")),
                rs.getString("exception_type"),
                rs.getString("severity"),
                rs.getString("status"),
                nullableUuid(rs.getString("subject_user_id")),
                rs.getString("subject_name"),
                rs.getString("employee_id"),
                nullableUuid(rs.getString("intern_lifecycle_id")),
                nullableUuid(rs.getString("assigned_to_id")),
                rs.getString("assigned_to_name"),
                nullableUuid(rs.getString("manager_id")),
                rs.getString("manager_name"),
                nullableUuid(rs.getString("erm_id")),
                rs.getString("erm_owner_name"),
                openedAt,
                rs.getTimestamp("last_seen_at") != null
                        ? rs.getTimestamp("last_seen_at").toInstant() : null,
                rs.getTimestamp("assigned_at") != null
                        ? rs.getTimestamp("assigned_at").toInstant() : null,
                rs.getTimestamp("resolved_at") != null
                        ? rs.getTimestamp("resolved_at").toInstant() : null,
                ageDays,
                rs.getString("subject_resource_type"),
                nullableUuid(rs.getString("subject_resource_id")),
                rs.getString("payload_json"));
    }

    private List<ManagerDtos.UserOption> distinctRoleAssignees(String sql) {
        List<ManagerDtos.UserOption> out = new ArrayList<>();
        try {
            jdbc.query(sql, rs -> {
                out.add(new ManagerDtos.UserOption(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("full_name")));
            });
        } catch (Exception e) {
            log.warn("[ManagerRisk] distinct query failed: {}", e.getMessage());
        }
        return out;
    }

    private long safeCount(String sql) {
        try {
            Long v = jdbc.queryForObject(sql, Long.class);
            return v != null ? v : 0L;
        } catch (Exception e) {
            log.warn("[ManagerRisk] count failed: {}", e.getMessage());
            return 0L;
        }
    }

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.toString();
    }

    private static UUID nullableUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }
}
