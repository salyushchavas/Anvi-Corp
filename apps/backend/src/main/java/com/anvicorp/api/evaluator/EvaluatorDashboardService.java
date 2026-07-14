package com.anvicorp.api.evaluator;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Evaluator Phase 1 — Home dashboard service.
 *
 * <p>Six KPIs cover this Evaluator's current workload:</p>
 * <ol>
 *   <li>Active Evaluees — intern_lifecycles assigned to caller, active.</li>
 *   <li>Evaluations This Month — PUBLISHED rows authored by caller in the
 *       current calendar month.</li>
 *   <li>Pending Acknowledgments — PUBLISHED but not yet
 *       intern_acknowledged_at, &lt; 14 days since publish.</li>
 *   <li>Overdue Evaluations — active evaluees with no PUBLISHED evaluation
 *       in the current monthly period.</li>
 *   <li>STEM OPT Interns — work_authorization_records.work_auth_type =
 *       F1_STEM_OPT among caller's active evaluees.</li>
 *   <li>Upcoming I-983 — placeholder (returns 0); Phase 3 implements the
 *       real federal cadence detector.</li>
 * </ol>
 *
 * <p>SUPER_ADMIN sees the org-wide aggregate (no evaluator_id scope).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvaluatorDashboardService {

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public EvaluatorDtos.DashboardResponse getDashboard(User caller) {
        UUID evaluatorId = caller.getId();
        boolean orgWide = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);
        YearMonth month = YearMonth.now();
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth().plusDays(1);

        List<EvaluatorDtos.KpiSnapshot> kpis = new ArrayList<>();
        kpis.add(kpiActiveEvaluees(evaluatorId, orgWide));
        kpis.add(kpiEvaluationsThisMonth(evaluatorId, orgWide, monthStart, monthEnd));
        kpis.add(kpiPendingAcknowledgments(evaluatorId, orgWide));
        kpis.add(kpiOverdueEvaluations(evaluatorId, orgWide, monthStart));
        kpis.add(kpiStemOptInterns(evaluatorId, orgWide));
        kpis.add(kpiUpcomingI983());

        EvaluatorDtos.CallerView callerView = new EvaluatorDtos.CallerView(
                caller.getId(), caller.getFullName(), caller.getEmail());
        return new EvaluatorDtos.DashboardResponse(
                callerView,
                month.toString(),
                kpis);
    }

    // ── KPI implementations ──────────────────────────────────────────────

    private EvaluatorDtos.KpiSnapshot kpiActiveEvaluees(UUID evaluatorId, boolean orgWide) {
        String where = " WHERE active_status = 'ACTIVE' ";
        long total;
        if (orgWide) {
            total = safeCount("SELECT COUNT(*) FROM intern_lifecycles " + where);
        } else {
            total = safeCount("SELECT COUNT(*) FROM intern_lifecycles "
                    + where + " AND evaluator_id = ?", evaluatorId);
        }
        return new EvaluatorDtos.KpiSnapshot(
                "ACTIVE_EVALUEES",
                "Active Evaluees",
                total, 0L,
                total == 0 ? "No interns assigned yet" : null,
                "/careers/evaluator/active-evaluees");
    }

    private EvaluatorDtos.KpiSnapshot kpiEvaluationsThisMonth(
            UUID evaluatorId, boolean orgWide,
            LocalDate monthStart, LocalDate monthEnd) {
        String base = "SELECT COUNT(*) FROM intern_evaluations "
                + " WHERE status = 'PUBLISHED' "
                + "   AND published_at >= ?::timestamp "
                + "   AND published_at < ?::timestamp ";
        long total;
        long urgent;
        if (orgWide) {
            total = safeCount(base, monthStart.toString(), monthEnd.toString());
            urgent = safeCount(base
                    + " AND published_at > NOW() - INTERVAL '7 days' ",
                    monthStart.toString(), monthEnd.toString());
        } else {
            total = safeCount(base + " AND evaluator_id = ? ",
                    monthStart.toString(), monthEnd.toString(), evaluatorId);
            urgent = safeCount(base
                    + " AND evaluator_id = ? "
                    + " AND published_at > NOW() - INTERVAL '7 days' ",
                    monthStart.toString(), monthEnd.toString(), evaluatorId);
        }
        return new EvaluatorDtos.KpiSnapshot(
                "EVALUATIONS_THIS_MONTH",
                "Evaluations This Month",
                total, urgent,
                urgent > 0 ? urgent + " published in the last 7 days" : null,
                "/careers/evaluator/evaluation-history?month=current");
    }

    private EvaluatorDtos.KpiSnapshot kpiPendingAcknowledgments(
            UUID evaluatorId, boolean orgWide) {
        String base = "SELECT COUNT(*) FROM intern_evaluations "
                + " WHERE status = 'PUBLISHED' "
                + "   AND intern_acknowledged_at IS NULL "
                + "   AND published_at > NOW() - INTERVAL '14 days' ";
        String urgentExtra = " AND published_at < NOW() - INTERVAL '7 days' ";
        long total;
        long urgent;
        if (orgWide) {
            total = safeCount(base);
            urgent = safeCount(base + urgentExtra);
        } else {
            total = safeCount(base + " AND evaluator_id = ? ", evaluatorId);
            urgent = safeCount(base + " AND evaluator_id = ? " + urgentExtra, evaluatorId);
        }
        return new EvaluatorDtos.KpiSnapshot(
                "PENDING_ACKNOWLEDGMENTS",
                "Pending Acknowledgments",
                total, urgent,
                urgent > 0 ? urgent + " waiting > 7 days" : null,
                "/careers/evaluator/evaluation-history?filter=unacknowledged");
    }

    private EvaluatorDtos.KpiSnapshot kpiOverdueEvaluations(
            UUID evaluatorId, boolean orgWide, LocalDate monthStart) {
        // Active evaluees with NO PUBLISHED evaluation in the current month.
        String base = "SELECT COUNT(*) FROM intern_lifecycles il "
                + " WHERE il.active_status = 'ACTIVE' "
                + "   AND NOT EXISTS ( "
                + "       SELECT 1 FROM intern_evaluations ev "
                + "        WHERE ev.intern_lifecycle_id = il.id "
                + "          AND ev.status = 'PUBLISHED' "
                + "          AND ev.published_at >= ?::timestamp "
                + "   ) ";
        long total;
        if (orgWide) {
            total = safeCount(base, monthStart.toString());
        } else {
            total = safeCount(base + " AND il.evaluator_id = ? ",
                    monthStart.toString(), evaluatorId);
        }
        return new EvaluatorDtos.KpiSnapshot(
                "OVERDUE_EVALUATIONS",
                "Overdue Evaluations",
                total, total,  // all overdue = urgent
                total > 0 ? "No evaluation logged this month" : null,
                "/careers/evaluator/active-evaluees?filter=overdue");
    }

    private EvaluatorDtos.KpiSnapshot kpiStemOptInterns(UUID evaluatorId, boolean orgWide) {
        String base = "SELECT COUNT(*) FROM intern_lifecycles il "
                + " JOIN work_authorization_records w ON w.user_id = il.user_id "
                + " WHERE il.active_status = 'ACTIVE' "
                + "   AND w.work_auth_type = 'F1_STEM_OPT' ";
        long total;
        if (orgWide) {
            total = safeCount(base);
        } else {
            total = safeCount(base + " AND il.evaluator_id = ? ", evaluatorId);
        }
        return new EvaluatorDtos.KpiSnapshot(
                "STEM_OPT_INTERNS",
                "STEM OPT Interns",
                total, 0L,
                total > 0 ? "I-983 evaluations required" : null,
                "/careers/evaluator/i983-evaluations");
    }

    private EvaluatorDtos.KpiSnapshot kpiUpcomingI983() {
        // Phase 1 placeholder. Phase 3 implements the federal cadence detector
        // (semi-annual + final submissions, 30-day pre-due alerts).
        return new EvaluatorDtos.KpiSnapshot(
                "UPCOMING_I983",
                "Upcoming I-983",
                0L, 0L,
                "Live count ships in Phase 3",
                "/careers/evaluator/i983-evaluations?filter=upcoming");
    }

    private long safeCount(String sql, Object... params) {
        try {
            Long v = jdbc.queryForObject(sql, Long.class, params);
            return v != null ? v : 0L;
        } catch (Exception e) {
            log.warn("[EvaluatorDashboard] count failed: {}", e.getMessage());
            return 0L;
        }
    }
}
