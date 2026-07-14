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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Manager Phase 2 — Onboarding Health, read-only. Portfolio-wide for
 *  MANAGER + SUPER_ADMIN. Population: users with
 *  {@code lifecycle_status IN (OFFER_SIGNED, EMPLOYEE_ID_CREATED,
 *  ONBOARDING_ASSIGNED, ONBOARDING_ACCEPTED)} — i.e. signed offer through
 *  the onboarding window.
 *
 *  <p>Surfaces document-task and compliance gate STATUS only — never
 *  form contents, encrypted fields, or {@code internalNote}-class
 *  ERM-only text. Mirrors the existing {@link com.anvicorp.api.erm.documents.DocumentPacketService}
 *  GROUP-BY-status aggregate pattern so Manager counts reconcile with
 *  ERM's document-review screen to the row.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerOnboardingService {

    /** Days before/after start date that count as "at risk" while
     *  onboarding isn't accepted. Matches the 7-day cadence the ERM
     *  dashboard already uses for "signed > 7 days ago" / "pending
     *  review > 7 days". */
    private static final int START_DATE_RISK_WINDOW_DAYS = 7;

    /** The "expiring soon" window for work-auth validity, same as ERM
     *  Phase 5 compliance KPI. */
    private static final int WORK_AUTH_EXPIRING_DAYS = 30;

    private static final List<String> ONBOARDING_STAGES = Arrays.asList(
            "OFFER_SIGNED",
            "EMPLOYEE_ID_CREATED",
            "ONBOARDING_ASSIGNED",
            "ONBOARDING_ACCEPTED");

    private final JdbcTemplate jdbc;

    public ManagerDtos.OnboardingResponse list(
            User caller,
            String stage,
            String workAuthType,
            UUID ermOwner,
            String needsAttention,
            String search,
            int page,
            int pageSize) {

        // RBAC is enforced by @PreAuthorize on the controller; this guard
        // is a belt-and-braces for direct service calls.
        if (caller == null || caller.getRoles() == null
                || (!caller.getRoles().contains(UserRole.MANAGER)
                    && !caller.getRoles().contains(UserRole.SUPER_ADMIN))) {
            throw new SecurityException("Manager or SUPER_ADMIN required");
        }

        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, pageSize), 100);
        int offset = safePage * safeSize;

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (stage != null && !stage.isBlank() && !"ALL".equals(stage)
                && ONBOARDING_STAGES.contains(stage)) {
            where.append(" AND u.lifecycle_status = ? ");
            params.add(stage);
        } else {
            where.append(" AND u.lifecycle_status IN (?,?,?,?) ");
            params.addAll(ONBOARDING_STAGES);
        }
        if (workAuthType != null && !workAuthType.isBlank()) {
            where.append(" AND war.work_auth_type = ? ");
            params.add(workAuthType);
        }
        if (ermOwner != null) {
            where.append(" AND il.erm_id = ? ");
            params.add(ermOwner);
        }
        if (search != null && !search.isBlank()) {
            String like = "%" + search.trim().toLowerCase() + "%";
            where.append(" AND (LOWER(u.full_name) LIKE ? "
                    + " OR LOWER(u.email) LIKE ? "
                    + " OR LOWER(COALESCE(il.employee_id,'')) LIKE ?) ");
            params.add(like); params.add(like); params.add(like);
        }

        // "Needs attention" — rejected docs, I-9 overdue, E-Verify overdue,
        // or start-date in/at the risk window. Computed against the same
        // CTE-style subqueries the main SELECT uses, kept as WHERE clauses
        // so total + items agree.
        if ("true".equalsIgnoreCase(needsAttention)) {
            where.append(" AND ( ")
                    .append("    EXISTS (SELECT 1 FROM document_tasks dt ")
                    .append("            JOIN document_packets dp2 ON dp2.id = dt.packet_id ")
                    .append("            WHERE dp2.intern_lifecycle_id = il.id ")
                    .append("              AND dt.status IN ('REJECTED','RESEND_REQUESTED')) ")
                    .append("    OR (i9.section_2_due_date IS NOT NULL ")
                    .append("        AND i9.section_2_due_date < CURRENT_DATE ")
                    .append("        AND i9.status <> 'COMPLETED') ")
                    .append("    OR (ev.due_by IS NOT NULL ")
                    .append("        AND ev.due_by < CURRENT_DATE ")
                    .append("        AND ev.status NOT IN ('EMPLOYMENT_AUTHORIZED','CLOSED')) ")
                    .append("    OR (il.tentative_start_date IS NOT NULL ")
                    .append("        AND il.tentative_start_date <= CURRENT_DATE + INTERVAL '")
                    .append(START_DATE_RISK_WINDOW_DAYS).append(" days' ")
                    .append("        AND u.lifecycle_status <> 'ONBOARDING_ACCEPTED') ")
                    .append(") ");
        }

        String fromAndJoins = ""
                + "  FROM users u "
                + "  JOIN intern_lifecycles il ON il.user_id = u.id "
                + "  LEFT JOIN work_authorization_records war ON war.user_id = u.id "
                + "  LEFT JOIN document_packets dp ON dp.intern_lifecycle_id = il.id "
                + "       AND dp.status <> 'CANCELLED' "
                + "  LEFT JOIN candidates c ON c.user_id = u.id "
                + "  LEFT JOIN i9_forms i9 ON i9.candidate_id = c.id "
                + "  LEFT JOIN everify_cases ev ON ev.i9_form_id = i9.id ";

        long total = 0L;
        try {
            Long v = jdbc.queryForObject(
                    "SELECT COUNT(DISTINCT u.id) " + fromAndJoins + where,
                    Long.class, params.toArray());
            total = v != null ? v : 0L;
        } catch (Exception e) {
            log.warn("[ManagerOnboarding] count failed: {}", e.getMessage());
        }

        String sql = ""
                + "SELECT u.id AS user_id, u.full_name, u.email, "
                + "       il.id AS lifecycle_id, il.employee_id, "
                + "       il.tentative_start_date, "
                + "       il.erm_id, il.manager_id, "
                + "       (SELECT u2.full_name FROM users u2 WHERE u2.id = il.erm_id) AS erm_owner_name, "
                + "       (SELECT u3.full_name FROM users u3 WHERE u3.id = il.manager_id) AS manager_name, "
                + "       u.lifecycle_status, "
                + "       war.work_auth_type, war.authorized_until, war.ead_expiration, war.i20_expiration, "
                + "       dp.id AS packet_id, dp.status AS packet_status, "
                + "       (SELECT COUNT(*) FROM document_tasks dt WHERE dt.packet_id = dp.id) AS task_total, "
                + "       (SELECT COUNT(*) FROM document_tasks dt WHERE dt.packet_id = dp.id AND dt.status = 'ACCEPTED') AS task_accepted, "
                + "       (SELECT COUNT(*) FROM document_tasks dt WHERE dt.packet_id = dp.id AND dt.status IN ('SUBMITTED','UNDER_REVIEW')) AS task_submitted, "
                + "       (SELECT COUNT(*) FROM document_tasks dt WHERE dt.packet_id = dp.id AND dt.status = 'PENDING') AS task_pending, "
                + "       (SELECT COUNT(*) FROM document_tasks dt WHERE dt.packet_id = dp.id AND dt.status IN ('REJECTED','RESEND_REQUESTED')) AS task_rejected, "
                + "       (SELECT COUNT(*) FROM document_tasks dt WHERE dt.packet_id = dp.id AND dt.status = 'WAIVED') AS task_waived, "
                + "       (SELECT MAX(dt.reviewed_at) FROM document_tasks dt WHERE dt.packet_id = dp.id) AS task_last_reviewed, "
                + "       i9.status AS i9_status, i9.section_2_due_date AS i9_due, "
                + "       ev.status AS everify_status, ev.due_by AS everify_due "
                + fromAndJoins
                + where
                + " ORDER BY il.tentative_start_date ASC NULLS LAST, u.full_name ASC "
                + " LIMIT " + safeSize + " OFFSET " + offset;

        List<ManagerDtos.OnboardingRow> rows = new ArrayList<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        try {
            rows = jdbc.query(sql, params.toArray(), (rs, n) -> mapRow(rs, today));
        } catch (Exception e) {
            log.warn("[ManagerOnboarding] list failed: {}", e.getMessage());
        }

        int totalPages = safeSize == 0 ? 0 : (int) Math.ceil(total / (double) safeSize);
        return new ManagerDtos.OnboardingResponse(rows, safePage, safeSize, total, totalPages);
    }

    public ManagerDtos.OnboardingSummary summary(User caller) {
        long offersAwaitingSignature = safeCount("SELECT COUNT(*) FROM offers WHERE status = 'SENT'");
        long newHiresOnboarding = safeCount(
                "SELECT COUNT(*) FROM users "
                        + " WHERE lifecycle_status IN ('OFFER_SIGNED','EMPLOYEE_ID_CREATED','ONBOARDING_ASSIGNED')");
        long onboardingAccepted = safeCount(
                "SELECT COUNT(*) FROM users WHERE lifecycle_status = 'ONBOARDING_ACCEPTED'");
        long i9Overdue = safeCount(
                "SELECT COUNT(*) "
                        + "  FROM i9_forms i9 "
                        + "  JOIN candidates c ON c.id = i9.candidate_id "
                        + "  JOIN users u ON u.id = c.user_id "
                        + " WHERE u.lifecycle_status IN ('OFFER_SIGNED','EMPLOYEE_ID_CREATED','ONBOARDING_ASSIGNED','ONBOARDING_ACCEPTED') "
                        + "   AND i9.section_2_due_date IS NOT NULL "
                        + "   AND i9.section_2_due_date < CURRENT_DATE "
                        + "   AND i9.status <> 'COMPLETED'");
        long everifyOverdue = safeCount(
                "SELECT COUNT(*) "
                        + "  FROM everify_cases ev "
                        + "  JOIN i9_forms i9 ON i9.id = ev.i9_form_id "
                        + "  JOIN candidates c ON c.id = i9.candidate_id "
                        + "  JOIN users u ON u.id = c.user_id "
                        + " WHERE u.lifecycle_status IN ('OFFER_SIGNED','EMPLOYEE_ID_CREATED','ONBOARDING_ASSIGNED','ONBOARDING_ACCEPTED') "
                        + "   AND ev.due_by IS NOT NULL "
                        + "   AND ev.due_by < CURRENT_DATE "
                        + "   AND ev.status NOT IN ('EMPLOYMENT_AUTHORIZED','CLOSED')");
        long startDateAtRisk = safeCount(
                "SELECT COUNT(*) "
                        + "  FROM users u "
                        + "  JOIN intern_lifecycles il ON il.user_id = u.id "
                        + " WHERE u.lifecycle_status IN ('OFFER_SIGNED','EMPLOYEE_ID_CREATED','ONBOARDING_ASSIGNED') "
                        + "   AND il.tentative_start_date IS NOT NULL "
                        + "   AND il.tentative_start_date <= CURRENT_DATE + INTERVAL '"
                        + START_DATE_RISK_WINDOW_DAYS + " days'");

        return new ManagerDtos.OnboardingSummary(
                offersAwaitingSignature, newHiresOnboarding, onboardingAccepted,
                i9Overdue, everifyOverdue, startDateAtRisk);
    }

    public ManagerDtos.OnboardingFilterOptions filterOptions() {
        List<String> workAuthTypes = new ArrayList<>();
        try {
            jdbc.query(
                    "SELECT DISTINCT work_auth_type FROM work_authorization_records "
                            + " WHERE work_auth_type IS NOT NULL ORDER BY work_auth_type",
                    rs -> { workAuthTypes.add(rs.getString(1)); });
        } catch (Exception e) {
            log.warn("[ManagerOnboarding] work_auth_type distinct failed: {}", e.getMessage());
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
            log.warn("[ManagerOnboarding] erm owner distinct failed: {}", e.getMessage());
        }
        return new ManagerDtos.OnboardingFilterOptions(ONBOARDING_STAGES, workAuthTypes, owners);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private ManagerDtos.OnboardingRow mapRow(java.sql.ResultSet rs, LocalDate today)
            throws java.sql.SQLException {
        UUID lifecycleId = UUID.fromString(rs.getString("lifecycle_id"));
        UUID userId = UUID.fromString(rs.getString("user_id"));
        String lifecycleStatus = rs.getString("lifecycle_status");
        LocalDate startDate = rs.getDate("tentative_start_date") != null
                ? rs.getDate("tentative_start_date").toLocalDate() : null;
        Long daysUntilStart = startDate != null
                ? ChronoUnit.DAYS.between(today, startDate) : null;
        boolean startDateAtRisk = startDate != null
                && !"ONBOARDING_ACCEPTED".equals(lifecycleStatus)
                && !startDate.isAfter(today.plusDays(START_DATE_RISK_WINDOW_DAYS));

        ManagerDtos.DocumentSummary docs = null;
        String packetId = rs.getString("packet_id");
        if (packetId != null) {
            docs = new ManagerDtos.DocumentSummary(
                    rs.getString("packet_status"),
                    rs.getInt("task_total"),
                    rs.getInt("task_accepted"),
                    rs.getInt("task_submitted"),
                    rs.getInt("task_pending"),
                    rs.getInt("task_rejected"),
                    rs.getInt("task_waived"),
                    rs.getInt("task_rejected") > 0,
                    rs.getTimestamp("task_last_reviewed") != null
                            ? rs.getTimestamp("task_last_reviewed").toInstant() : null);
        }

        String i9Status = rs.getString("i9_status");
        LocalDate i9Due = rs.getDate("i9_due") != null
                ? rs.getDate("i9_due").toLocalDate() : null;
        boolean i9Overdue = i9Due != null && i9Due.isBefore(today)
                && i9Status != null && !"COMPLETED".equals(i9Status);

        String everifyStatus = rs.getString("everify_status");
        LocalDate everifyDue = rs.getDate("everify_due") != null
                ? rs.getDate("everify_due").toLocalDate() : null;
        boolean everifyOverdue = everifyDue != null && everifyDue.isBefore(today)
                && everifyStatus != null
                && !"EMPLOYMENT_AUTHORIZED".equals(everifyStatus)
                && !"CLOSED".equals(everifyStatus);

        java.sql.Date authorizedUntilSql = rs.getDate("authorized_until");
        java.sql.Date eadSql = rs.getDate("ead_expiration");
        java.sql.Date i20Sql = rs.getDate("i20_expiration");
        LocalDate workAuthValidUntil = earliest(
                authorizedUntilSql != null ? authorizedUntilSql.toLocalDate() : null,
                eadSql != null ? eadSql.toLocalDate() : null,
                i20Sql != null ? i20Sql.toLocalDate() : null);
        boolean workAuthExpiringSoon = workAuthValidUntil != null
                && !workAuthValidUntil.isAfter(today.plusDays(WORK_AUTH_EXPIRING_DAYS));

        ManagerDtos.ComplianceSummary compliance = new ManagerDtos.ComplianceSummary(
                i9Status, i9Due, i9Overdue,
                everifyStatus, everifyDue, everifyOverdue,
                workAuthValidUntil, workAuthExpiringSoon);

        return new ManagerDtos.OnboardingRow(
                lifecycleId, userId,
                rs.getString("full_name"), rs.getString("email"),
                rs.getString("employee_id"),
                rs.getString("work_auth_type"),
                lifecycleStatus,
                startDate, daysUntilStart, startDateAtRisk,
                docs, compliance,
                nullableUuid(rs.getString("erm_id")), rs.getString("erm_owner_name"),
                nullableUuid(rs.getString("manager_id")), rs.getString("manager_name"));
    }

    private static LocalDate earliest(LocalDate... dates) {
        LocalDate min = null;
        for (LocalDate d : dates) {
            if (d == null) continue;
            if (min == null || d.isBefore(min)) min = d;
        }
        return min;
    }

    private long safeCount(String sql) {
        try {
            Long v = jdbc.queryForObject(sql, Long.class);
            return v != null ? v : 0L;
        } catch (Exception e) {
            log.warn("[ManagerOnboarding] count failed ({}): {}", sql, e.getMessage());
            return 0L;
        }
    }

    private static UUID nullableUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }
}
