package com.anvicorp.api.evaluator;

import com.anvicorp.api.common.MonthRange;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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
    public EvaluatorDtos.DashboardResponse getDashboard(User caller, MonthRange range) {
        UUID evaluatorId = caller.getId();
        boolean orgWide = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);
        // First day of the selected month; end = first day of the
        // following month (half-open, matches the existing ?::timestamp
        // idiom the KPI queries already use).
        LocalDate monthStart = range.monthStart();
        LocalDate monthEnd = range.monthEnd();

        List<EvaluatorDtos.KpiSnapshot> kpis = new ArrayList<>();
        // ACTIVE_EVALUEES — rescopes on selected month via overlap
        // predicate (started_at < end AND (ended_at IS NULL OR ended_at
        // >= start)). Current-month is naturally identical to today's
        // "active_status = ACTIVE" set because active rows have
        // ended_at IS NULL; past months surface interns who were
        // active at that time.
        kpis.add(kpiActiveEvaluees(evaluatorId, orgWide, range));
        kpis.add(kpiEvaluationsThisMonth(evaluatorId, orgWide, monthStart, monthEnd));
        // PENDING_ACKNOWLEDGMENTS — bounded by the selected month's
        // published_at window (replaces the 14-day rolling window when
        // an explicit month is selected).
        kpis.add(kpiPendingAcknowledgments(evaluatorId, orgWide, monthStart, monthEnd));
        kpis.add(kpiOverdueEvaluations(evaluatorId, orgWide, monthStart, monthEnd));
        // Live-queue KPIs — leave as-is. STEM OPT is a "who currently
        // needs I-983 attention" count, not a month-scoped one.
        kpis.add(kpiStemOptInterns(evaluatorId, orgWide));
        kpis.add(kpiUpcomingI983());

        EvaluatorDtos.CallerView callerView = new EvaluatorDtos.CallerView(
                caller.getId(), caller.getFullName(), caller.getEmail());
        return new EvaluatorDtos.DashboardResponse(
                callerView,
                range.label(),
                kpis);
    }

    // ── KPI implementations ──────────────────────────────────────────────

    private EvaluatorDtos.KpiSnapshot kpiActiveEvaluees(UUID evaluatorId, boolean orgWide,
                                                         MonthRange range) {
        // Overlap predicate: any lifecycle whose active window
        // intersects the selected month counts as "active this month".
        // Current-month reduces to today's live set because a still-
        // running lifecycle has ended_at IS NULL. Past-months surface
        // interns who were active back then.
        String where = " WHERE started_at IS NOT NULL "
                + "   AND started_at < ?::timestamp "
                + "   AND (ended_at IS NULL OR ended_at >= ?::timestamp) ";
        String monthEnd = range.endDateString();
        String monthStart = range.startDateString();
        long total;
        if (orgWide) {
            total = safeCount("SELECT COUNT(*) FROM intern_lifecycles " + where,
                    monthEnd, monthStart);
        } else {
            total = safeCount("SELECT COUNT(*) FROM intern_lifecycles "
                    + where + " AND evaluator_id = ?",
                    monthEnd, monthStart, evaluatorId);
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
            UUID evaluatorId, boolean orgWide,
            LocalDate monthStart, LocalDate monthEnd) {
        // Bounded by the selected month's publish window (replaces the
        // 14-day rolling window). Current-month sees every unack'd
        // publish issued this month; past-months surface unack'd
        // publishes issued back then that never got acknowledged.
        String base = "SELECT COUNT(*) FROM intern_evaluations "
                + " WHERE status = 'PUBLISHED' "
                + "   AND intern_acknowledged_at IS NULL "
                + "   AND published_at >= ?::timestamp "
                + "   AND published_at < ?::timestamp ";
        String urgentExtra = " AND published_at < NOW() - INTERVAL '7 days' ";
        long total;
        long urgent;
        if (orgWide) {
            total = safeCount(base, monthStart.toString(), monthEnd.toString());
            urgent = safeCount(base + urgentExtra,
                    monthStart.toString(), monthEnd.toString());
        } else {
            total = safeCount(base + " AND evaluator_id = ? ",
                    monthStart.toString(), monthEnd.toString(), evaluatorId);
            urgent = safeCount(base + " AND evaluator_id = ? " + urgentExtra,
                    monthStart.toString(), monthEnd.toString(), evaluatorId);
        }
        return new EvaluatorDtos.KpiSnapshot(
                "PENDING_ACKNOWLEDGMENTS",
                "Pending Acknowledgments",
                total, urgent,
                urgent > 0 ? urgent + " waiting > 7 days" : null,
                "/careers/evaluator/evaluation-history?filter=unacknowledged");
    }

    private EvaluatorDtos.KpiSnapshot kpiOverdueEvaluations(
            UUID evaluatorId, boolean orgWide,
            LocalDate monthStart, LocalDate monthEnd) {
        // Evaluees who were active within the selected month AND had
        // no PUBLISHED evaluation dated inside that month. Uses the
        // same overlap predicate as kpiActiveEvaluees so the "active
        // this month" universe agrees; scopes the NOT-EXISTS window to
        // the month explicitly instead of "since start of month" so
        // past-month views don't count publishes issued afterwards.
        String base = "SELECT COUNT(*) FROM intern_lifecycles il "
                + " WHERE il.started_at IS NOT NULL "
                + "   AND il.started_at < ?::timestamp "
                + "   AND (il.ended_at IS NULL OR il.ended_at >= ?::timestamp) "
                + "   AND NOT EXISTS ( "
                + "       SELECT 1 FROM intern_evaluations ev "
                + "        WHERE ev.intern_lifecycle_id = il.id "
                + "          AND ev.status = 'PUBLISHED' "
                + "          AND ev.published_at >= ?::timestamp "
                + "          AND ev.published_at < ?::timestamp "
                + "   ) ";
        long total;
        if (orgWide) {
            total = safeCount(base,
                    monthEnd.toString(), monthStart.toString(),
                    monthStart.toString(), monthEnd.toString());
        } else {
            total = safeCount(base + " AND il.evaluator_id = ? ",
                    monthEnd.toString(), monthStart.toString(),
                    monthStart.toString(), monthEnd.toString(), evaluatorId);
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
