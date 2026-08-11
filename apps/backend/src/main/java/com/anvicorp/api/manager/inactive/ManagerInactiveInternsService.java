package com.anvicorp.api.manager.inactive;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.repository.InternEvaluationRepository;
import com.anvicorp.api.repository.ProjectAssignmentRepository;
import com.anvicorp.api.repository.ProjectRepository;
import com.anvicorp.api.repository.TimesheetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 4B-1 — Manager's read-only Inactive Interns list. Org-wide for
 * any MANAGER (and SUPER_ADMIN) — matches the rest of the Manager
 * surfaces (overview, pipeline, onboarding, risk-center, timesheet
 * list) which are portfolio-wide per the canonical design. An optional
 * period filter on {@code exit_records.exit_date} narrows the result
 * set when {@code y/m} are supplied.
 *
 * <p>The earlier per-manager fence ({@code manager_id = caller.id} for
 * non-SUPER_ADMIN) made newly-active interns with null
 * {@code manager_id} invisible to anyone — paired with the timesheet
 * approval deadlock that's now also resolved.</p>
 *
 * <p>Reuses {@link com.anvicorp.api.entity.ExitRecord} as the source
 * of closure data — mirrors {@code ExitService.getInternSummary} for
 * the closure snapshot (projects completed, evaluations, hours) so the
 * Manager view and the intern's own exit card stay consistent.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerInactiveInternsService {

    private static final ZoneId ZONE = ZoneId.of("America/Chicago");

    private final JdbcTemplate jdbc;
    private final ProjectAssignmentRepository projectAssignmentRepository;
    private final ProjectRepository projectRepository;
    private final InternEvaluationRepository internEvaluationRepository;
    private final TimesheetRepository timesheetRepository;

    @Transactional(readOnly = true)
    public ManagerInactiveInternsDtos.InactiveInternsListResponse list(
            User caller, Integer year, Integer month) {
        return list(caller, year, month, false);
    }

    /**
     * B3-remainder overload — {@code terminatedOnly=true} narrows the
     * inactive-interns query to just the TERMINATED cohort (management-
     * initiated separations), the shape the /manager/terminated-interns
     * section renders. Kept as an overload of the existing all-inactives
     * list so the two views share every join, DTO mapping, and closure-
     * snapshot enrichment — the only difference is the WHERE clause
     * predicate.
     */
    @Transactional(readOnly = true)
    public ManagerInactiveInternsDtos.InactiveInternsListResponse list(
            User caller, Integer year, Integer month, boolean terminatedOnly) {
        requireManagerOrSuperAdmin(caller);

        // Build period filter (optional). Default = all time.
        YearMonth period = null;
        java.sql.Date exitFromInclusive = null, exitToExclusive = null;
        if (year != null && month != null) {
            period = YearMonth.of(year, month);
            exitFromInclusive = java.sql.Date.valueOf(period.atDay(1));
            exitToExclusive = java.sql.Date.valueOf(period.plusMonths(1).atDay(1));
        }

        // TERMINATED = ERM/Manager-initiated separation (recorded on
        // il.active_status by ExitService via
        // mapToLifecycleActiveStatus). All-inactive default catches
        // both the lifecycle_status flip AND the granular exit types.
        StringBuilder where = new StringBuilder(
                terminatedOnly
                        ? " WHERE il.active_status = 'TERMINATED' "
                        : " WHERE (u.lifecycle_status = 'INACTIVE_INTERN' "
                                + "      OR il.active_status IN ('COMPLETED','RESIGNED','TERMINATED')) ");
        List<Object> params = new ArrayList<>();
        // Manager scope is org-wide — no manager_id filter. SUPER_ADMIN
        // and MANAGER see the same set; the role gate ran above.
        if (period != null) {
            // Period applies to exit_date when an ExitRecord exists; we
            // OR with lifecycle.ended_at so rows without a record still
            // honour the filter consistently.
            where.append(" AND ( "
                    + "    (er.exit_date >= ? AND er.exit_date < ?) "
                    + " OR (er.id IS NULL AND il.ended_at >= ? AND il.ended_at < ?) "
                    + " ) ");
            params.add(exitFromInclusive);
            params.add(exitToExclusive);
            params.add(Timestamp.from(period.atDay(1).atStartOfDay(ZONE).toInstant()));
            params.add(Timestamp.from(period.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant()));
        }

        String sql = "SELECT il.id AS lifecycle_id, il.user_id, u.full_name, u.email, "
                + "       il.employee_id, il.active_status, il.started_at, il.hired_at, il.ended_at, "
                + "       tu.full_name AS trainer_name, eu.full_name AS evaluator_name, "
                + "       mu.full_name AS manager_name, ru.full_name AS erm_name, "
                + "       c.id AS candidate_id, c.skillset, "
                + "       er.id AS exit_id, er.exit_type, er.exit_date, er.last_working_day, "
                + "       er.exit_reason, er.reason_code, er.rehire_eligible, "
                + "       er.final_timesheet_status, er.access_revocation_done, "
                + "       er.final_documents_archived, er.intern_visible_summary, "
                + "       er.final_evaluation_id "
                + "  FROM intern_lifecycles il "
                + "  JOIN users u ON u.id = il.user_id "
                + "  LEFT JOIN candidates c ON c.user_id = il.user_id "
                + "  LEFT JOIN users tu ON tu.id = il.trainer_id "
                + "  LEFT JOIN users eu ON eu.id = il.evaluator_id "
                + "  LEFT JOIN users mu ON mu.id = il.manager_id "
                + "  LEFT JOIN users ru ON ru.id = il.erm_id "
                + "  LEFT JOIN exit_records er ON er.intern_lifecycle_id = il.id "
                + where
                + " ORDER BY COALESCE(er.exit_date, il.ended_at::date) DESC NULLS LAST, "
                + "          u.full_name ASC ";

        List<ManagerInactiveInternsDtos.InactiveInternRow> rows = new ArrayList<>();
        try {
            rows = jdbc.query(sql, params.toArray(), (rs, n) -> {
                UUID lifecycleId = uuidOf(rs.getString("lifecycle_id"));
                UUID internUserId = uuidOf(rs.getString("user_id"));
                UUID candidateId = uuidOf(rs.getString("candidate_id"));
                Instant startedAt = instantOf(rs.getTimestamp("started_at"));
                if (startedAt == null) startedAt = instantOf(rs.getTimestamp("hired_at"));
                Instant endedAt = instantOf(rs.getTimestamp("ended_at"));
                LocalDate startDate = startedAt != null
                        ? startedAt.atZone(ZONE).toLocalDate() : null;
                long durationDays = (startedAt != null)
                        ? java.time.Duration.between(startedAt,
                                endedAt != null ? endedAt : Instant.now()).toDays()
                        : 0L;
                if (durationDays < 0) durationDays = 0;

                // Closure aggregates — bulk repo reads per row. The
                // page is intrinsically small (one Manager's inactive
                // cohort), so N+1 here is fine.
                long projectsCompleted = candidateId == null ? 0L : projectAssignmentRepository
                        .findByInternIdOrderByAssignmentDateDescCreatedAtDesc(internUserId)
                        .stream()
                        .filter(a -> a.getStatus() != null
                                && "COMPLETED".equals(a.getStatus().name()))
                        .count();

                long evalCount = 0L;
                Double avgScore = null;
                if (internUserId != null) {
                    var evals = internEvaluationRepository
                            .findByInternIdOrderByCreatedAtDesc(internUserId).stream()
                            .filter(e -> e.getStatus() != null
                                    && (e.getStatus().equals("PUBLISHED")
                                    || e.getStatus().equals("ACKNOWLEDGED")
                                    || e.getStatus().equals("AMENDED")))
                            .toList();
                    evalCount = evals.size();
                    var withScore = evals.stream()
                            .filter(e -> e.getOverallScore() != null)
                            .mapToInt(e -> e.getOverallScore())
                            .toArray();
                    if (withScore.length > 0) {
                        double sum = 0;
                        for (int s : withScore) sum += s;
                        avgScore = sum / withScore.length;
                    }
                }
                BigDecimal totalApprovedHours = candidateId != null
                        ? timesheetRepository.sumApprovedHoursForIntern(candidateId)
                        : BigDecimal.ZERO;
                if (totalApprovedHours == null) totalApprovedHours = BigDecimal.ZERO;

                String lastProjectTitle = null;
                LocalDate lastProjectMonthAnchor = null;
                if (lifecycleId != null) {
                    try {
                        var projects = projectRepository
                                .findByInternLifecycleIdOrderByMonthYearDescProjectNumberAsc(lifecycleId);
                        if (!projects.isEmpty()) {
                            var top = projects.get(0);
                            lastProjectTitle = top.getName() != null
                                    ? top.getName() : top.getTitle();
                            String my = top.getMonthYear();
                            if (my != null && my.length() == 7) {
                                try {
                                    lastProjectMonthAnchor = LocalDate.parse(my + "-01");
                                } catch (Exception ignored) {}
                            }
                        }
                    } catch (Exception ignored) {}
                }

                return new ManagerInactiveInternsDtos.InactiveInternRow(
                        lifecycleId,
                        internUserId,
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getString("employee_id"),
                        rs.getString("skillset"),
                        rs.getString("active_status"),
                        endedAt,
                        startDate,
                        durationDays,
                        rs.getString("trainer_name"),
                        rs.getString("evaluator_name"),
                        rs.getString("manager_name"),
                        rs.getString("erm_name"),
                        uuidOf(rs.getString("exit_id")),
                        rs.getString("exit_type"),
                        rs.getDate("exit_date") != null
                                ? rs.getDate("exit_date").toLocalDate() : null,
                        rs.getDate("last_working_day") != null
                                ? rs.getDate("last_working_day").toLocalDate() : null,
                        rs.getString("exit_reason"),
                        rs.getString("reason_code"),
                        (Boolean) rs.getObject("rehire_eligible"),
                        rs.getString("final_timesheet_status"),
                        (Boolean) rs.getObject("access_revocation_done"),
                        (Boolean) rs.getObject("final_documents_archived"),
                        rs.getString("intern_visible_summary"),
                        uuidOf(rs.getString("final_evaluation_id")),
                        projectsCompleted,
                        evalCount,
                        avgScore,
                        totalApprovedHours,
                        lastProjectTitle,
                        lastProjectMonthAnchor);
            });
        } catch (Exception e) {
            log.warn("[ManagerInactiveInterns] list failed: {}", e.getMessage());
        }

        return new ManagerInactiveInternsDtos.InactiveInternsListResponse(
                rows, rows.size(),
                period != null ? period.toString() : null);
    }

    private void requireManagerOrSuperAdmin(User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required");
        if (caller.getRoles() == null
                || (!caller.getRoles().contains(UserRole.MANAGER)
                    && !caller.getRoles().contains(UserRole.SUPER_ADMIN))) {
            throw new ForbiddenException("MANAGER or SUPER_ADMIN required");
        }
    }

    private static UUID uuidOf(String s) {
        if (s == null) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    private static Instant instantOf(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
