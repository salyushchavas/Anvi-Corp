package com.anvicorp.api.evaluator;

import com.anvicorp.api.common.MonthRange;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Evaluator Phase 1 — right-side panel context.
 *
 * <p>Two modes:
 * <ul>
 *   <li>Home (no lifecycleId): aggregate counts for the cycle.</li>
 *   <li>Evaluee detail (lifecycleId provided): per-intern context.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvaluatorRightPanelService {

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public EvaluatorDtos.RightPanelResponse get(UUID lifecycleId, User caller, MonthRange range) {
        String monthLabel = range.label();
        if (lifecycleId == null) {
            return new EvaluatorDtos.RightPanelResponse(
                    monthLabel, loadHomeAggregate(caller, range), null);
        }
        return new EvaluatorDtos.RightPanelResponse(
                monthLabel, null, loadEvalueeContext(lifecycleId));
    }

    private EvaluatorDtos.HomeAggregate loadHomeAggregate(User caller, MonthRange range) {
        boolean orgWide = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);
        UUID evaluatorId = caller.getId();
        LocalDate monthStart = range.monthStart();
        LocalDate monthEnd = range.monthEnd();

        // Active-evaluees — overlap predicate, same shape as the
        // dashboard's ACTIVE_EVALUEES tile. Current-month reduces to
        // the live set (ended_at IS NULL); past-months surface interns
        // who were active back then.
        long active = orgWide
                ? safe("SELECT COUNT(*) FROM intern_lifecycles "
                        + "WHERE started_at IS NOT NULL "
                        + "AND started_at < ?::timestamp "
                        + "AND (ended_at IS NULL OR ended_at >= ?::timestamp)",
                        monthEnd.toString(), monthStart.toString())
                : safe("SELECT COUNT(*) FROM intern_lifecycles "
                        + "WHERE started_at IS NOT NULL "
                        + "AND started_at < ?::timestamp "
                        + "AND (ended_at IS NULL OR ended_at >= ?::timestamp) "
                        + "AND evaluator_id = ?",
                        monthEnd.toString(), monthStart.toString(), evaluatorId);
        long published = orgWide
                ? safe("SELECT COUNT(*) FROM intern_evaluations "
                        + "WHERE status = 'PUBLISHED' "
                        + "AND published_at >= ?::timestamp "
                        + "AND published_at < ?::timestamp",
                        monthStart.toString(), monthEnd.toString())
                : safe("SELECT COUNT(*) FROM intern_evaluations "
                        + "WHERE status = 'PUBLISHED' "
                        + "AND published_at >= ?::timestamp "
                        + "AND published_at < ?::timestamp "
                        + "AND evaluator_id = ?",
                        monthStart.toString(), monthEnd.toString(), evaluatorId);
        // Pending-acks — bounded by the selected month's publish window
        // (replaces the 14-day rolling window when a month is selected).
        long pendingAcks = orgWide
                ? safe("SELECT COUNT(*) FROM intern_evaluations "
                        + "WHERE status = 'PUBLISHED' "
                        + "AND intern_acknowledged_at IS NULL "
                        + "AND published_at >= ?::timestamp "
                        + "AND published_at < ?::timestamp",
                        monthStart.toString(), monthEnd.toString())
                : safe("SELECT COUNT(*) FROM intern_evaluations "
                        + "WHERE status = 'PUBLISHED' "
                        + "AND intern_acknowledged_at IS NULL "
                        + "AND published_at >= ?::timestamp "
                        + "AND published_at < ?::timestamp "
                        + "AND evaluator_id = ?",
                        monthStart.toString(), monthEnd.toString(), evaluatorId);
        return new EvaluatorDtos.HomeAggregate(active, published, pendingAcks);
    }

    private EvaluatorDtos.EvalueePanelContext loadEvalueeContext(UUID lifecycleId) {
        try {
            List<EvaluatorDtos.EvalueePanelContext> rows = jdbc.query(
                    "SELECT il.id, u.full_name AS intern_name, il.employee_id, "
                            + "(SELECT jp.title FROM applications a "
                            + "   JOIN job_postings jp ON jp.id = a.job_posting_id "
                            + "  WHERE a.candidate_id IN ( "
                            + "      SELECT id FROM candidates c WHERE c.user_id = il.user_id) "
                            + "  ORDER BY a.applied_at DESC LIMIT 1) AS technology, "
                            + "w.work_auth_type, il.started_at, "
                            + "lastEv.published_at AS last_eval_at, "
                            + "lastEv.status AS last_eval_status "
                            + "FROM intern_lifecycles il "
                            + "JOIN users u ON u.id = il.user_id "
                            + "LEFT JOIN work_authorization_records w ON w.user_id = il.user_id "
                            + "LEFT JOIN LATERAL ( "
                            + "    SELECT ev.published_at, ev.status FROM intern_evaluations ev "
                            + "     WHERE ev.intern_lifecycle_id = il.id "
                            + "       AND ev.status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED') "
                            + "     ORDER BY ev.published_at DESC NULLS LAST LIMIT 1 "
                            + ") lastEv ON TRUE "
                            + "WHERE il.id = ?",
                    (rs, n) -> {
                        Instant startedAt = rs.getTimestamp("started_at") != null
                                ? rs.getTimestamp("started_at").toInstant() : null;
                        int months = startedAt != null
                                ? (int) ChronoUnit.MONTHS.between(
                                        startedAt.atOffset(ZoneOffset.UTC)
                                                .toLocalDate().withDayOfMonth(1),
                                        LocalDate.now().withDayOfMonth(1))
                                : 0;
                        String workAuth = rs.getString("work_auth_type");
                        return new EvaluatorDtos.EvalueePanelContext(
                                UUID.fromString(rs.getString("id")),
                                rs.getString("intern_name"),
                                rs.getString("employee_id"),
                                rs.getString("technology"),
                                workAuth,
                                Math.max(0, months),
                                rs.getTimestamp("last_eval_at") != null
                                        ? rs.getTimestamp("last_eval_at").toInstant() : null,
                                rs.getString("last_eval_status"),
                                "F1_STEM_OPT".equalsIgnoreCase(workAuth));
                    }, lifecycleId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.warn("[EvaluatorRightPanel] evaluee context query failed: {}",
                    e.getMessage());
            return null;
        }
    }

    private long safe(String sql, Object... params) {
        try {
            Long v = jdbc.queryForObject(sql, Long.class, params);
            return v != null ? v : 0L;
        } catch (Exception e) {
            log.warn("[EvaluatorRightPanel] safeCount failed: {}", e.getMessage());
            return 0L;
        }
    }
}
