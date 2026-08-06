package com.anvicorp.api.manager.portfolio;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manager Intern Portfolio — read-only aggregation across every intern
 * on the roster regardless of stage. Backs {@code /careers/manager/intern-portfolio}.
 *
 * <p><strong>Exclusion rules (server-side, never optional):</strong></p>
 * <ul>
 *   <li>{@code intern_lifecycles.deleted_at IS NULL} — soft-deleted rows
 *       (from the two-tier admin purge) never surface.</li>
 *   <li>{@code users.email_verified = true} — unverified accounts (bot
 *       signups, half-completed registrations) never surface.</li>
 * </ul>
 *
 * <p>List query shape: single SELECT joining {@code intern_lifecycles}
 * to {@code users} + four LEFT JOIN aliases for the trainer / evaluator
 * / erm / manager name resolution + a lateral for the latest application
 * summary. No N+1 across the roster — hydration for each row is
 * O(1) column reads.</p>
 *
 * <p>Detail query shape: one query per section (profile, contact,
 * address, education, work_auth, application, documents, offers,
 * projects+evals, team, exit). Sections that read from the same table
 * share a query when the SQL is trivially joinable (contact + profile).
 * Total: ~8 queries per detail load — well below any perceptible
 * latency for a click-through detail page.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerInternPortfolioService {

    private final JdbcTemplate jdbc;

    // ── List ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ManagerInternPortfolioDtos.PortfolioListPage list(
            User caller,
            String search,
            String stage,           // ALL | ACCOUNT_CREATED | ONBOARDING | ACTIVE | EXITED
            int page,
            int pageSize) {

        requireManagerOrSuperAdmin(caller);
        int p = Math.max(0, page);
        int ps = Math.min(Math.max(1, pageSize), 100);

        StringBuilder where = new StringBuilder(BASE_WHERE);
        List<Object> params = new ArrayList<>();
        appendSearch(where, params, search);

        // Aggregate stage counts BEFORE applying the stage filter so the
        // tab badges reflect the whole filter-set universe.
        ManagerInternPortfolioDtos.StageCounts counts = countByStage(where.toString(), params);

        String stageClause = stageClauseFor(stage);
        StringBuilder filteredWhere = new StringBuilder(where);
        if (!stageClause.isEmpty()) {
            filteredWhere.append(" AND ").append(stageClause);
        }

        long total = countOne(
                "SELECT COUNT(*) " + FROM_JOINS + filteredWhere,
                params.toArray());

        String pageSql = "SELECT " + SELECT_COLS + " " + FROM_JOINS + filteredWhere
                + " ORDER BY u.created_at DESC NULLS LAST, u.id ASC "
                + " LIMIT " + ps + " OFFSET " + (p * ps);
        List<ManagerInternPortfolioDtos.PortfolioRow> rows = new ArrayList<>();
        try {
            rows = jdbc.query(pageSql, params.toArray(), this::mapRow);
        } catch (Exception e) {
            log.warn("[ManagerPortfolio.list] query failed: {}", e.getMessage());
        }

        int totalPages = ps == 0 ? 0 : (int) Math.ceil((double) total / ps);
        return new ManagerInternPortfolioDtos.PortfolioListPage(
                rows, p, ps, total, totalPages, counts);
    }

    // ── Detail ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ManagerInternPortfolioDtos.PortfolioDetail getDetail(User caller, UUID userId) {
        requireManagerOrSuperAdmin(caller);
        // Same exclusion rules as the list — a manager can only open the
        // detail for a portfolio-eligible row.
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap(
                    "SELECT u.id AS user_id, u.full_name, u.email, u.phone_number, "
                            + "       u.email_verified, u.created_at AS joined_at, "
                            + "       u.applicant_id, "
                            + "       il.id AS lifecycle_id, il.employee_id, il.active_status, "
                            + "       il.hired_at, il.started_at, il.ended_at, "
                            + "       il.trainer_id, il.evaluator_id, il.manager_id, il.erm_id "
                            + "  FROM users u "
                            + "  LEFT JOIN intern_lifecycles il "
                            + "         ON il.user_id = u.id AND il.deleted_at IS NULL "
                            + " WHERE u.id = ? "
                            + "   AND u.email_verified = true ",
                    userId);
        } catch (Exception e) {
            throw new ResourceNotFoundException(
                    "Intern not found in portfolio (unverified, soft-deleted, or missing).");
        }

        String activeStatus = (String) row.get("active_status");
        Instant hiredAt = instantOf(row.get("hired_at"));
        Instant startedAt = instantOf(row.get("started_at"));
        Instant endedAt = instantOf(row.get("ended_at"));
        String stage = derivedStage(row.get("lifecycle_id"), activeStatus, endedAt);
        UUID lifecycleId = uuidOf(row.get("lifecycle_id"));
        UUID uid = uuidOf(row.get("user_id"));
        String fullName = (String) row.get("full_name");
        String primaryEmail = (String) row.get("email");
        int monthsInProgram = monthsInProgram(startedAt, hiredAt);

        var profile = new ManagerInternPortfolioDtos.ProfileSection(
                uid, lifecycleId, fullName,
                (String) row.get("employee_id"),
                (String) row.get("applicant_id"),
                stage, stageLabel(stage),
                instantOf(row.get("joined_at")),
                hiredAt, startedAt, endedAt,
                monthsInProgram);
        var contact = new ManagerInternPortfolioDtos.ContactSection(
                primaryEmail,
                (String) row.get("phone_number"),
                loadCompanyEmail(uid));
        var address = loadAddress(uid);
        var education = loadEducation(uid);
        var workAuth = loadWorkAuth(uid, lifecycleId);
        var application = loadApplicationSummary(uid);
        var documents = loadDocuments(lifecycleId);
        var offers = loadOffers(uid);
        var projectsAndEvals = loadProjectsAndEvals(lifecycleId);
        var team = loadTeam(row);
        var exit = loadExit(lifecycleId, activeStatus, endedAt);

        return new ManagerInternPortfolioDtos.PortfolioDetail(
                profile, contact, address, education, workAuth,
                application, documents, offers, projectsAndEvals,
                team, exit);
    }

    // ── List query building blocks ───────────────────────────────────────

    private static final String SELECT_COLS =
            "u.id AS user_id, u.full_name, u.email, u.applicant_id, "
                    + "u.created_at AS joined_at, "
                    + "il.id AS lifecycle_id, il.employee_id, il.active_status, "
                    + "il.hired_at, il.started_at, il.ended_at, "
                    + "tu.full_name AS trainer_name, "
                    + "eu.full_name AS evaluator_name, "
                    + "ru.full_name AS erm_name";

    private static final String FROM_JOINS =
            "FROM users u "
                    + "LEFT JOIN intern_lifecycles il "
                    + "       ON il.user_id = u.id AND il.deleted_at IS NULL "
                    + "LEFT JOIN users tu ON tu.id = il.trainer_id "
                    + "LEFT JOIN users eu ON eu.id = il.evaluator_id "
                    + "LEFT JOIN users ru ON ru.id = il.erm_id ";

    /** Baseline exclusions — verified accounts only; if a lifecycle
     *  exists it must NOT be soft-deleted. Users without a lifecycle
     *  (pre-hire "account created" stage) still show up because the
     *  LEFT JOIN keeps them in the result set. */
    private static final String BASE_WHERE =
            "WHERE u.email_verified = true "
                    // Restrict to users who have EITHER a lifecycle row OR
                    // are pipeline candidates (an application exists) — this
                    // keeps random staff / role accounts out of the intern
                    // portfolio (a manager viewing "all interns" shouldn't
                    // see other managers or evaluators).
                    + "  AND ( il.id IS NOT NULL "
                    + "     OR EXISTS ( "
                    + "          SELECT 1 FROM candidates c "
                    + "           WHERE c.user_id = u.id) ) ";

    private void appendSearch(StringBuilder where, List<Object> params, String search) {
        if (search == null || search.isBlank()) return;
        String like = "%" + search.trim().toLowerCase() + "%";
        if (like.length() > 122) like = like.substring(0, 122);
        where.append(" AND (LOWER(u.full_name) LIKE ? "
                + "OR LOWER(u.email) LIKE ? "
                + "OR LOWER(COALESCE(u.applicant_id,'')) LIKE ? "
                + "OR LOWER(COALESCE(il.employee_id,'')) LIKE ?) ");
        params.add(like); params.add(like); params.add(like); params.add(like);
    }

    /**
     * Stage derivation (in the SQL so it filters + sorts identically to
     * the Java-side derivedStage helper used on the detail path):
     *   no lifecycle                           → ACCOUNT_CREATED
     *   lifecycle + active_status='PROSPECTIVE'→ ONBOARDING
     *   lifecycle + active_status='ACTIVE'     → ACTIVE
     *   lifecycle + ended_at IS NOT NULL       → EXITED
     *   anything else                          → ACCOUNT_CREATED (defensive)
     */
    private String stageClauseFor(String stage) {
        if (stage == null || stage.isBlank() || "ALL".equalsIgnoreCase(stage)) return "";
        switch (stage.toUpperCase()) {
            case "ACCOUNT_CREATED":
                return " il.id IS NULL ";
            case "ONBOARDING":
                return " il.id IS NOT NULL AND il.active_status = 'PROSPECTIVE' "
                        + "   AND il.ended_at IS NULL ";
            case "ACTIVE":
                return " il.id IS NOT NULL AND il.active_status = 'ACTIVE' "
                        + "   AND il.ended_at IS NULL ";
            case "EXITED":
                return " il.id IS NOT NULL AND il.ended_at IS NOT NULL ";
            default:
                return "";
        }
    }

    private ManagerInternPortfolioDtos.StageCounts countByStage(String whereBase, List<Object> params) {
        long created = countOne(
                "SELECT COUNT(*) " + FROM_JOINS + whereBase + " AND il.id IS NULL",
                params.toArray());
        long onboarding = countOne(
                "SELECT COUNT(*) " + FROM_JOINS + whereBase
                        + " AND il.id IS NOT NULL AND il.active_status = 'PROSPECTIVE' AND il.ended_at IS NULL",
                params.toArray());
        long active = countOne(
                "SELECT COUNT(*) " + FROM_JOINS + whereBase
                        + " AND il.id IS NOT NULL AND il.active_status = 'ACTIVE' AND il.ended_at IS NULL",
                params.toArray());
        long exited = countOne(
                "SELECT COUNT(*) " + FROM_JOINS + whereBase
                        + " AND il.id IS NOT NULL AND il.ended_at IS NOT NULL",
                params.toArray());
        return new ManagerInternPortfolioDtos.StageCounts(
                created, onboarding, active, exited, created + onboarding + active + exited);
    }

    private ManagerInternPortfolioDtos.PortfolioRow mapRow(ResultSet rs, int n) throws SQLException {
        String activeStatus = rs.getString("active_status");
        Instant endedAt = rs.getTimestamp("ended_at") != null
                ? rs.getTimestamp("ended_at").toInstant() : null;
        String lifecycleIdRaw = rs.getString("lifecycle_id");
        String stage = derivedStage(lifecycleIdRaw, activeStatus, endedAt);
        return new ManagerInternPortfolioDtos.PortfolioRow(
                UUID.fromString(rs.getString("user_id")),
                lifecycleIdRaw != null ? UUID.fromString(lifecycleIdRaw) : null,
                rs.getString("full_name"),
                rs.getString("email"),
                rs.getString("employee_id"),
                rs.getString("applicant_id"),
                stage, stageLabel(stage),
                rs.getString("trainer_name"),
                rs.getString("evaluator_name"),
                rs.getString("erm_name"),
                rs.getTimestamp("joined_at") != null
                        ? rs.getTimestamp("joined_at").toInstant() : null,
                rs.getTimestamp("hired_at") != null
                        ? rs.getTimestamp("hired_at").toInstant() : null,
                rs.getTimestamp("started_at") != null
                        ? rs.getTimestamp("started_at").toInstant() : null,
                endedAt);
    }

    // ── Detail sub-loaders ───────────────────────────────────────────────

    private String loadCompanyEmail(UUID userId) {
        try {
            return jdbc.queryForObject(
                    "SELECT address FROM mail_accounts "
                            + " WHERE user_id = ? AND status = 'ACTIVE' "
                            + " ORDER BY created_at DESC LIMIT 1",
                    String.class, userId);
        } catch (Exception ignored) { return null; }
    }

    private ManagerInternPortfolioDtos.AddressSection loadAddress(UUID userId) {
        try {
            return jdbc.queryForObject(
                    "SELECT address_line1, address_line2, city, state, "
                            + "       postal_code, country "
                            + "  FROM candidates WHERE user_id = ? "
                            + " ORDER BY created_at DESC LIMIT 1",
                    (rs, n) -> new ManagerInternPortfolioDtos.AddressSection(
                            rs.getString("address_line1"),
                            rs.getString("address_line2"),
                            rs.getString("city"),
                            rs.getString("state"),
                            rs.getString("postal_code"),
                            rs.getString("country")),
                    userId);
        } catch (Exception ignored) { return null; }
    }

    private ManagerInternPortfolioDtos.EducationSection loadEducation(UUID userId) {
        try {
            return jdbc.queryForObject(
                    "SELECT highest_degree, field_of_study, university, "
                            + "       graduation_year "
                            + "  FROM candidates WHERE user_id = ? "
                            + " ORDER BY created_at DESC LIMIT 1",
                    (rs, n) -> new ManagerInternPortfolioDtos.EducationSection(
                            rs.getString("highest_degree"),
                            rs.getString("field_of_study"),
                            rs.getString("university"),
                            rs.getString("graduation_year")),
                    userId);
        } catch (Exception ignored) { return null; }
    }

    private ManagerInternPortfolioDtos.WorkAuthSection loadWorkAuth(UUID userId, UUID lifecycleId) {
        String workAuthType = null;
        LocalDate authExpires = null;
        try {
            Map<String, Object> war = jdbc.queryForMap(
                    "SELECT work_auth_type, auth_expires_on FROM work_authorization_records "
                            + " WHERE user_id = ? ORDER BY created_at DESC LIMIT 1", userId);
            workAuthType = (String) war.get("work_auth_type");
            java.sql.Date d = (java.sql.Date) war.get("auth_expires_on");
            authExpires = d != null ? d.toLocalDate() : null;
        } catch (Exception ignored) { /* no war row */ }
        String planStatus = null;
        try {
            planStatus = jdbc.queryForObject(
                    "SELECT ip.status FROM i983_plans ip "
                            + " JOIN candidates c ON c.id = ip.candidate_id "
                            + " WHERE c.user_id = ? ORDER BY ip.created_at DESC LIMIT 1",
                    String.class, userId);
        } catch (Exception ignored) { /* no plan */ }
        if (workAuthType == null && planStatus == null && authExpires == null) return null;
        return new ManagerInternPortfolioDtos.WorkAuthSection(
                workAuthType, authExpires, planStatus);
    }

    private ManagerInternPortfolioDtos.ApplicationSummary loadApplicationSummary(UUID userId) {
        try {
            return jdbc.queryForObject(
                    "SELECT a.id AS application_id, jp.title AS job_title, "
                            + "       a.status, a.applied_at, "
                            + "       a.last_decision_at, "
                            + "       du.full_name AS decision_by "
                            + "  FROM applications a "
                            + "  JOIN candidates c ON c.id = a.candidate_id "
                            + "  LEFT JOIN job_postings jp ON jp.id = a.job_posting_id "
                            + "  LEFT JOIN users du ON du.id = a.last_decision_by_id "
                            + " WHERE c.user_id = ? "
                            + " ORDER BY a.applied_at DESC LIMIT 1",
                    (rs, n) -> new ManagerInternPortfolioDtos.ApplicationSummary(
                            UUID.fromString(rs.getString("application_id")),
                            rs.getString("job_title"),
                            rs.getString("status"),
                            rs.getTimestamp("applied_at") != null
                                    ? rs.getTimestamp("applied_at").toInstant() : null,
                            rs.getTimestamp("last_decision_at") != null
                                    ? rs.getTimestamp("last_decision_at").toInstant() : null,
                            rs.getString("decision_by")),
                    userId);
        } catch (Exception ignored) { return null; }
    }

    private ManagerInternPortfolioDtos.DocumentsSection loadDocuments(UUID lifecycleId) {
        if (lifecycleId == null) {
            return new ManagerInternPortfolioDtos.DocumentsSection(
                    null, "NONE", 0, 0, 0);
        }
        try {
            return jdbc.queryForObject(
                    "SELECT dp.id AS packet_id, dp.status AS packet_status, "
                            + "  COALESCE((SELECT COUNT(*) FROM document_tasks dt "
                            + "              WHERE dt.document_packet_id = dp.id), 0) AS total_tasks, "
                            + "  COALESCE((SELECT COUNT(*) FROM document_tasks dt "
                            + "              WHERE dt.document_packet_id = dp.id "
                            + "                AND dt.status IN ('SUBMITTED','APPROVED','RESUBMISSION_REQUESTED')), 0) AS submitted_tasks, "
                            + "  COALESCE((SELECT COUNT(*) FROM document_tasks dt "
                            + "              WHERE dt.document_packet_id = dp.id "
                            + "                AND dt.status = 'APPROVED'), 0) AS reviewed_tasks "
                            + "  FROM document_packets dp "
                            + " WHERE dp.intern_lifecycle_id = ? "
                            + "   AND dp.status NOT IN ('CANCELLED') "
                            + " ORDER BY dp.assigned_at DESC NULLS LAST LIMIT 1",
                    (rs, n) -> {
                        String packetIdRaw = rs.getString("packet_id");
                        return new ManagerInternPortfolioDtos.DocumentsSection(
                                packetIdRaw != null ? UUID.fromString(packetIdRaw) : null,
                                rs.getString("packet_status"),
                                rs.getInt("total_tasks"),
                                rs.getInt("submitted_tasks"),
                                rs.getInt("reviewed_tasks"));
                    },
                    lifecycleId);
        } catch (Exception e) {
            return new ManagerInternPortfolioDtos.DocumentsSection(
                    null, "NONE", 0, 0, 0);
        }
    }

    private ManagerInternPortfolioDtos.OffersSection loadOffers(UUID userId) {
        List<ManagerInternPortfolioDtos.OfferHistoryRow> rows = new ArrayList<>();
        try {
            rows = jdbc.query(
                    "SELECT o.id AS offer_id, o.offer_number, o.status, "
                            + "       o.sent_at, o.signed_at, o.replaced_at, "
                            + "       o.replaced_by_reason "
                            + "  FROM offers o "
                            + "  JOIN applications a ON a.id = o.application_id "
                            + "  JOIN candidates c ON c.id = a.candidate_id "
                            + " WHERE c.user_id = ? "
                            + " ORDER BY o.sent_at DESC NULLS LAST",
                    (rs, n) -> {
                        String status = rs.getString("status");
                        return new ManagerInternPortfolioDtos.OfferHistoryRow(
                                UUID.fromString(rs.getString("offer_id")),
                                rs.getString("offer_number"),
                                status,
                                offerStatusLabel(status),
                                rs.getTimestamp("sent_at") != null
                                        ? rs.getTimestamp("sent_at").toInstant() : null,
                                rs.getTimestamp("signed_at") != null
                                        ? rs.getTimestamp("signed_at").toInstant() : null,
                                rs.getTimestamp("replaced_at") != null
                                        ? rs.getTimestamp("replaced_at").toInstant() : null,
                                null,      // revoked_at not modelled distinctly today
                                rs.getString("replaced_by_reason"));
                    }, userId);
        } catch (Exception e) {
            log.debug("[ManagerPortfolio.offers] query fallback: {}", e.getMessage());
        }
        return new ManagerInternPortfolioDtos.OffersSection(rows);
    }

    private ManagerInternPortfolioDtos.ProjectsAndEvalsSection loadProjectsAndEvals(UUID lifecycleId) {
        if (lifecycleId == null) {
            return new ManagerInternPortfolioDtos.ProjectsAndEvalsSection(
                    0, 0, 0, null, List.of());
        }
        int totalProjects = 0;
        int completedProjects = 0;
        try {
            Map<String, Object> pc = jdbc.queryForMap(
                    "SELECT COUNT(*) AS total, "
                            + "  COUNT(*) FILTER (WHERE status = 'COMPLETED') AS completed "
                            + "  FROM projects WHERE intern_lifecycle_id = ?",
                    lifecycleId);
            totalProjects = ((Number) pc.get("total")).intValue();
            completedProjects = ((Number) pc.get("completed")).intValue();
        } catch (Exception ignored) {}
        int publishedEvals = 0;
        Double avgScore = null;
        try {
            Map<String, Object> ec = jdbc.queryForMap(
                    "SELECT COUNT(*) FILTER (WHERE status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED')) AS pub, "
                            + "  AVG(overall_score) FILTER (WHERE overall_score IS NOT NULL) AS avg_score "
                            + "  FROM intern_evaluations WHERE intern_lifecycle_id = ?",
                    lifecycleId);
            publishedEvals = ((Number) ec.get("pub")).intValue();
            Object avg = ec.get("avg_score");
            avgScore = avg != null ? ((Number) avg).doubleValue() : null;
        } catch (Exception ignored) {}
        List<ManagerInternPortfolioDtos.ProjectSummary> recent = new ArrayList<>();
        try {
            recent = jdbc.query(
                    "SELECT p.id, p.title, p.status, p.month_year, "
                            + "       p.started_at, p.completed_at, "
                            + "       ev.status AS eval_status, ev.overall_score "
                            + "  FROM projects p "
                            + "  LEFT JOIN intern_evaluations ev "
                            + "         ON ev.linked_project_id = p.id "
                            + "        AND ev.evaluation_type = 'POST_PROJECT' "
                            + " WHERE p.intern_lifecycle_id = ? "
                            + " ORDER BY p.created_at DESC LIMIT 8",
                    (rs, n) -> {
                        Object score = rs.getObject("overall_score");
                        return new ManagerInternPortfolioDtos.ProjectSummary(
                                UUID.fromString(rs.getString("id")),
                                rs.getString("title"),
                                rs.getString("status"),
                                rs.getString("month_year"),
                                rs.getTimestamp("started_at") != null
                                        ? rs.getTimestamp("started_at").toInstant() : null,
                                rs.getTimestamp("completed_at") != null
                                        ? rs.getTimestamp("completed_at").toInstant() : null,
                                rs.getString("eval_status"),
                                score != null ? ((Number) score).intValue() : null);
                    }, lifecycleId);
        } catch (Exception ignored) {}
        return new ManagerInternPortfolioDtos.ProjectsAndEvalsSection(
                totalProjects, completedProjects, publishedEvals, avgScore, recent);
    }

    private ManagerInternPortfolioDtos.TeamSection loadTeam(Map<String, Object> row) {
        UUID trainerId = uuidOf(row.get("trainer_id"));
        UUID evaluatorId = uuidOf(row.get("evaluator_id"));
        UUID managerId = uuidOf(row.get("manager_id"));
        UUID ermId = uuidOf(row.get("erm_id"));
        return new ManagerInternPortfolioDtos.TeamSection(
                resolveRef(trainerId),
                resolveRef(evaluatorId),
                resolveRef(managerId),
                resolveRef(ermId));
    }

    private ManagerInternPortfolioDtos.NamedRef resolveRef(UUID userId) {
        if (userId == null) return null;
        try {
            return jdbc.queryForObject(
                    "SELECT id, full_name, email FROM users WHERE id = ?",
                    (rs, n) -> new ManagerInternPortfolioDtos.NamedRef(
                            UUID.fromString(rs.getString("id")),
                            rs.getString("full_name"),
                            rs.getString("email")),
                    userId);
        } catch (Exception ignored) { return null; }
    }

    private ManagerInternPortfolioDtos.ExitSection loadExit(UUID lifecycleId, String activeStatus,
                                                              Instant endedAt) {
        if (lifecycleId == null || endedAt == null) return null;
        // Try the exit_records table first for a rich reason; fall back
        // to the active_status enum when no row exists (legacy paths).
        try {
            return jdbc.queryForObject(
                    "SELECT exit_type, exited_at, reason_summary "
                            + "  FROM exit_records "
                            + " WHERE intern_lifecycle_id = ? "
                            + "   AND deleted_at IS NULL "
                            + " ORDER BY exited_at DESC NULLS LAST LIMIT 1",
                    (rs, n) -> {
                        String t = rs.getString("exit_type");
                        Instant ea = rs.getTimestamp("exited_at") != null
                                ? rs.getTimestamp("exited_at").toInstant() : endedAt;
                        return new ManagerInternPortfolioDtos.ExitSection(
                                t, exitTypeLabel(t), ea, rs.getString("reason_summary"));
                    }, lifecycleId);
        } catch (Exception ignored) {
            // Fall back to active_status → exit_type mapping.
            String t = "EXITED".equals(activeStatus) ? "COMPLETED" : activeStatus;
            return new ManagerInternPortfolioDtos.ExitSection(
                    t, exitTypeLabel(t), endedAt, null);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static void requireManagerOrSuperAdmin(User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required");
        if (caller.getRoles() == null) throw new ForbiddenException("No role");
        if (caller.getRoles().contains(UserRole.SUPER_ADMIN)) return;
        if (caller.getRoles().contains(UserRole.MANAGER)) return;
        throw new ForbiddenException("MANAGER or SUPER_ADMIN required");
    }

    private long countOne(String sql, Object... params) {
        try {
            Long v = jdbc.queryForObject(sql, Long.class, params);
            return v != null ? v : 0L;
        } catch (Exception e) {
            log.debug("[ManagerPortfolio.count] fallback: {}", e.getMessage());
            return 0L;
        }
    }

    private static String derivedStage(Object lifecycleIdCell, String activeStatus, Instant endedAt) {
        if (lifecycleIdCell == null) return "ACCOUNT_CREATED";
        if (endedAt != null) return "EXITED";
        if ("ACTIVE".equals(activeStatus)) return "ACTIVE";
        if ("PROSPECTIVE".equals(activeStatus)) return "ONBOARDING";
        // COMPLETED / RESIGNED / TERMINATED without ended_at is legacy —
        // treat as EXITED since the enum says the engagement ended.
        if ("COMPLETED".equals(activeStatus) || "RESIGNED".equals(activeStatus)
                || "TERMINATED".equals(activeStatus)) return "EXITED";
        return "ACCOUNT_CREATED";
    }

    private static String stageLabel(String stage) {
        return switch (stage) {
            case "ACCOUNT_CREATED" -> "Account created";
            case "ONBOARDING"      -> "Onboarding";
            case "ACTIVE"          -> "Active";
            case "EXITED"          -> "Exited";
            default                -> stage;
        };
    }

    private static String offerStatusLabel(String status) {
        if (status == null) return "—";
        return switch (status) {
            case "SENT"      -> "Sent";
            case "SIGNED"    -> "Signed";
            case "EXECUTED"  -> "Executed";
            case "REPLACED"  -> "Replaced";
            case "REVOKED"   -> "Revoked";
            case "EXPIRED"   -> "Expired";
            case "DECLINED"  -> "Declined";
            case "WITHDRAWN" -> "Withdrawn";
            default          -> status;
        };
    }

    private static String exitTypeLabel(String type) {
        if (type == null) return "Exited";
        return switch (type) {
            case "COMPLETED"  -> "Completed program";
            case "RESIGNED"   -> "Resigned";
            case "TERMINATED" -> "Terminated";
            default            -> type;
        };
    }

    private static UUID uuidOf(Object cell) {
        if (cell == null) return null;
        if (cell instanceof UUID u) return u;
        try { return UUID.fromString(cell.toString()); } catch (Exception e) { return null; }
    }

    private static Instant instantOf(Object cell) {
        if (cell == null) return null;
        if (cell instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (cell instanceof Instant i) return i;
        return null;
    }

    private static int monthsInProgram(Instant startedAt, Instant hiredAt) {
        Instant anchor = startedAt != null ? startedAt : hiredAt;
        if (anchor == null) return 0;
        return (int) Math.max(0, ChronoUnit.MONTHS.between(
                anchor.atOffset(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1),
                LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)));
    }
}
