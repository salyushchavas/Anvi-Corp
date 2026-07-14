package com.anvicorp.api.evaluator;

import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.InternLifecycleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Evaluator Phase 1 — Active Evaluees list + per-evaluee detail.
 *
 * <p>Read-only. Phase 2 will add the actual evaluation composition flows
 * that live on top of these read endpoints.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvaluatorEvalueesService {

    private final JdbcTemplate jdbc;
    private final InternLifecycleRepository lifecycleRepository;
    private final EvaluatorScopeGuard evaluatorScopeGuard;

    // ── Active Evaluees list ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public EvaluatorDtos.ActiveEvalueesPage list(
            User caller,
            String search,
            String workAuthType,
            String needsAttention,
            int page, int pageSize) {

        boolean orgWide = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);
        Pageable pageable = PageRequest.of(
                Math.max(0, page), Math.min(Math.max(1, pageSize), 100));

        StringBuilder where = new StringBuilder(
                " WHERE il.active_status = 'ACTIVE' ");
        List<Object> params = new ArrayList<>();
        if (!orgWide) {
            // Single-evaluator fallback (matches EvaluatorScopeGuard):
            // include null-evaluator interns so the list never disagrees
            // with what the per-intern detail/write guards will allow.
            // Without this, a null-evaluator intern wouldn't appear here
            // even though the org evaluator could open + write them.
            where.append(" AND (il.evaluator_id = ? OR il.evaluator_id IS NULL) ");
            params.add(caller.getId());
        }
        if (search != null && !search.isBlank()) {
            String q = "%" + search.trim().toLowerCase() + "%";
            if (q.length() > 102) q = q.substring(0, 102);
            where.append(" AND (LOWER(u.full_name) LIKE ? "
                    + "OR LOWER(COALESCE(il.employee_id,'')) LIKE ? "
                    + "OR LOWER(COALESCE(u.applicant_id,'')) LIKE ?) ");
            params.add(q); params.add(q); params.add(q);
        }
        if (workAuthType != null && !workAuthType.isBlank()) {
            where.append(" AND COALESCE(w.work_auth_type,'') = ? ");
            params.add(workAuthType.trim().toUpperCase());
        }
        if ("true".equalsIgnoreCase(needsAttention)) {
            // last published evaluation > 30 days OR ≥1 pending acknowledgment
            where.append(" AND ( "
                    + "      lastEv.published_at IS NULL "
                    + "   OR lastEv.published_at < NOW() - INTERVAL '30 days' "
                    + "   OR pendingAck.cnt > 0 ) ");
        }

        String base = LIST_BASE_SELECT_PREFIX + where;

        long total;
        try {
            Long v = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ( " + base + " ) sub",
                    Long.class, params.toArray());
            total = v != null ? v : 0L;
        } catch (Exception e) {
            log.warn("[Evaluees.list] count failed: {}", e.getMessage());
            total = 0L;
        }

        String sql = base
                + " ORDER BY il.started_at ASC NULLS LAST, u.full_name ASC "
                + " LIMIT " + pageable.getPageSize()
                + " OFFSET " + (pageable.getPageNumber() * pageable.getPageSize());

        List<EvaluatorDtos.ActiveEvalueeRow> rows = new ArrayList<>();
        try {
            rows = jdbc.query(sql, params.toArray(), (rs, n) -> {
                Instant startedAt = rs.getTimestamp("started_at") != null
                        ? rs.getTimestamp("started_at").toInstant() : null;
                int months = startedAt != null
                        ? (int) ChronoUnit.MONTHS.between(
                                startedAt.atOffset(ZoneOffset.UTC).toLocalDate()
                                        .withDayOfMonth(1),
                                LocalDate.now().withDayOfMonth(1))
                        : 0;
                return new EvaluatorDtos.ActiveEvalueeRow(
                        UUID.fromString(rs.getString("lifecycle_id")),
                        UUID.fromString(rs.getString("user_id")),
                        rs.getString("intern_name"),
                        rs.getString("employee_id"),
                        rs.getString("technology"),
                        rs.getString("work_auth_type"),
                        startedAt,
                        Math.max(0, months),
                        rs.getTimestamp("last_eval_at") != null
                                ? rs.getTimestamp("last_eval_at").toInstant() : null,
                        rs.getString("last_eval_status"),
                        rs.getString("last_eval_type"),
                        rs.getInt("pending_ack_count"),
                        null,  // i983_due_within — Phase 3
                        rs.getString("trainer_name"),
                        rs.getString("erm_name"));
            });
        } catch (Exception e) {
            log.warn("[Evaluees.list] query failed: {}", e.getMessage());
        }

        int totalPages = pageable.getPageSize() == 0 ? 0
                : (int) Math.ceil(total / (double) pageable.getPageSize());
        return new EvaluatorDtos.ActiveEvalueesPage(
                rows, pageable.getPageNumber(), pageable.getPageSize(),
                total, totalPages);
    }

    private static final String LIST_BASE_SELECT_PREFIX =
            "SELECT il.id AS lifecycle_id, il.user_id, "
                    + "u.full_name AS intern_name, il.employee_id, "
                    + "(SELECT jp.title FROM applications a "
                    + "   JOIN job_postings jp ON jp.id = a.job_posting_id "
                    + "  WHERE a.candidate_id IN ( "
                    + "      SELECT id FROM candidates c WHERE c.user_id = il.user_id) "
                    + "  ORDER BY a.applied_at DESC LIMIT 1) AS technology, "
                    + "w.work_auth_type, il.started_at, "
                    + "lastEv.published_at AS last_eval_at, "
                    + "lastEv.status AS last_eval_status, "
                    + "lastEv.evaluation_type AS last_eval_type, "
                    + "COALESCE(pendingAck.cnt, 0)::int AS pending_ack_count, "
                    + "tu.full_name AS trainer_name, "
                    + "ru.full_name AS erm_name "
                    + "FROM intern_lifecycles il "
                    + "JOIN users u ON u.id = il.user_id "
                    + "LEFT JOIN work_authorization_records w ON w.user_id = il.user_id "
                    + "LEFT JOIN users tu ON tu.id = il.trainer_id "
                    + "LEFT JOIN users ru ON ru.id = il.erm_id "
                    + "LEFT JOIN LATERAL ( "
                    + "    SELECT ev.published_at, ev.status, ev.evaluation_type "
                    + "      FROM intern_evaluations ev "
                    + "     WHERE ev.intern_lifecycle_id = il.id "
                    + "       AND ev.status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED') "
                    + "     ORDER BY ev.published_at DESC NULLS LAST "
                    + "     LIMIT 1 "
                    + ") lastEv ON TRUE "
                    + "LEFT JOIN LATERAL ( "
                    + "    SELECT COUNT(*) AS cnt FROM intern_evaluations ev "
                    + "     WHERE ev.intern_lifecycle_id = il.id "
                    + "       AND ev.status = 'PUBLISHED' "
                    + "       AND ev.intern_acknowledged_at IS NULL "
                    + ") pendingAck ON TRUE ";

    // ── Evaluee detail ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public EvaluatorDtos.EvalueeDetail getDetail(UUID lifecycleId, User caller) {
        EvaluatorDtos.EvalueeProfile profile = loadProfile(lifecycleId);
        if (profile == null) {
            throw new ResourceNotFoundException(
                    "Evaluee not found: " + lifecycleId);
        }
        // Delegate to EvaluatorScopeGuard — same single-evaluator
        // null-fallback predicate the list() WHERE clause now uses.
        // Detail + list always agree: any row visible in list opens
        // without a 403 here.
        InternLifecycle lc = lifecycleRepository.findById(lifecycleId).orElse(null);
        evaluatorScopeGuard.requireEvaluatorOwnership(lc, caller);

        EvaluatorDtos.CurrentMonthCard currentMonth = loadCurrentMonth(lifecycleId);
        EvaluatorDtos.HistorySummaryCard historySummary = loadHistorySummary(lifecycleId);
        EvaluatorDtos.I983StatusCard i983 =
                "F1_STEM_OPT".equalsIgnoreCase(profile.workAuthType())
                        ? loadI983Card(lifecycleId, profile.internUserId())
                        : null;
        EvaluatorDtos.TrainerContextCard trainer = loadTrainerContext(lifecycleId);
        List<EvaluatorDtos.EvaluationTimelineEntry> timeline = loadTimeline(lifecycleId);

        return new EvaluatorDtos.EvalueeDetail(
                profile, currentMonth, historySummary, i983, trainer, timeline);
    }

    private EvaluatorDtos.EvalueeProfile loadProfile(UUID lifecycleId) {
        try {
            return jdbc.queryForObject(
                    "SELECT il.id, il.user_id, u.full_name, u.email, "
                            + "u.applicant_id, il.employee_id, il.started_at, "
                            + "w.work_auth_type, "
                            + "(SELECT jp.title FROM applications a "
                            + "   JOIN job_postings jp ON jp.id = a.job_posting_id "
                            + "  WHERE a.candidate_id IN ( "
                            + "      SELECT id FROM candidates c WHERE c.user_id = il.user_id) "
                            + "  ORDER BY a.applied_at DESC LIMIT 1) AS technology, "
                            + "(SELECT COUNT(*) FROM intern_evaluations ev "
                            + "  WHERE ev.intern_lifecycle_id = il.id) AS total_evals, "
                            + "(SELECT MAX(ev.published_at) FROM intern_evaluations ev "
                            + "  WHERE ev.intern_lifecycle_id = il.id "
                            + "    AND ev.status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED')) "
                            + "  AS last_eval_at "
                            + "  FROM intern_lifecycles il "
                            + "  JOIN users u ON u.id = il.user_id "
                            + "  LEFT JOIN work_authorization_records w ON w.user_id = il.user_id "
                            + " WHERE il.id = ?",
                    (rs, n) -> {
                        Instant startedAt = rs.getTimestamp("started_at") != null
                                ? rs.getTimestamp("started_at").toInstant() : null;
                        int months = startedAt != null
                                ? (int) ChronoUnit.MONTHS.between(
                                        startedAt.atOffset(ZoneOffset.UTC)
                                                .toLocalDate().withDayOfMonth(1),
                                        LocalDate.now().withDayOfMonth(1))
                                : 0;
                        return new EvaluatorDtos.EvalueeProfile(
                                UUID.fromString(rs.getString("id")),
                                UUID.fromString(rs.getString("user_id")),
                                rs.getString("full_name"),
                                rs.getString("email"),
                                rs.getString("applicant_id"),
                                rs.getString("employee_id"),
                                rs.getString("technology"),
                                rs.getString("work_auth_type"),
                                startedAt,
                                Math.max(0, months),
                                rs.getInt("total_evals"),
                                rs.getTimestamp("last_eval_at") != null
                                        ? rs.getTimestamp("last_eval_at").toInstant() : null);
                    },
                    lifecycleId);
        } catch (Exception e) {
            log.warn("[Evaluees.profile] query failed for {}: {}",
                    lifecycleId, e.getMessage());
            return null;
        }
    }

    private EvaluatorDtos.CurrentMonthCard loadCurrentMonth(UUID lifecycleId) {
        YearMonth month = YearMonth.now();
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth().plusDays(1);
        try {
            List<EvaluatorDtos.CurrentMonthCard> rows = jdbc.query(
                    "SELECT ev.id, ev.status, ev.published_at, "
                            + "       ev.intern_acknowledged_at "
                            + "  FROM intern_evaluations ev "
                            + " WHERE ev.intern_lifecycle_id = ? "
                            + "   AND ( ev.published_at IS NULL "
                            + "       OR (ev.published_at >= ?::timestamp "
                            + "         AND ev.published_at < ?::timestamp)) "
                            + " ORDER BY ev.created_at DESC LIMIT 1",
                    (rs, n) -> {
                        String status = rs.getString("status");
                        Instant pubAt = rs.getTimestamp("published_at") != null
                                ? rs.getTimestamp("published_at").toInstant() : null;
                        boolean ack = rs.getTimestamp("intern_acknowledged_at") != null;
                        Integer daysSince = (pubAt != null && !ack)
                                ? (int) ChronoUnit.DAYS.between(pubAt, Instant.now())
                                : null;
                        if (ack) status = "ACKNOWLEDGED";
                        return new EvaluatorDtos.CurrentMonthCard(
                                status, month.toString(),
                                UUID.fromString(rs.getString("id")),
                                pubAt, daysSince,
                                isActionNeeded(status, daysSince));
                    },
                    lifecycleId, monthStart.toString(), monthEnd.toString());
            if (!rows.isEmpty()) return rows.get(0);
        } catch (Exception e) {
            log.warn("[Evaluees.currentMonth] query failed: {}", e.getMessage());
        }
        return new EvaluatorDtos.CurrentMonthCard(
                "NOT_YET_SCHEDULED", month.toString(), null, null, null, true);
    }

    private static boolean isActionNeeded(String status, Integer daysSincePublish) {
        if (status == null) return true;
        return switch (status) {
            case "DRAFT", "SCHEDULED", "IN_PROGRESS" -> true;
            case "PUBLISHED" -> daysSincePublish != null && daysSincePublish > 7;
            default -> false;
        };
    }

    private EvaluatorDtos.HistorySummaryCard loadHistorySummary(UUID lifecycleId) {
        try {
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FILTER (WHERE status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED')) "
                            + "         AS total_published, "
                            + "       AVG(overall_score) FILTER (WHERE overall_score IS NOT NULL) "
                            + "         AS avg_score "
                            + "  FROM intern_evaluations "
                            + " WHERE intern_lifecycle_id = ?",
                    (rs, n) -> {
                        int total = rs.getInt("total_published");
                        Object avgObj = rs.getObject("avg_score");
                        Double avg = avgObj != null ? ((Number) avgObj).doubleValue() : null;
                        String trend = trendFor(lifecycleId);
                        return new EvaluatorDtos.HistorySummaryCard(total, avg, trend);
                    },
                    lifecycleId);
        } catch (Exception e) {
            log.warn("[Evaluees.history] query failed: {}", e.getMessage());
            return new EvaluatorDtos.HistorySummaryCard(0, null, "INSUFFICIENT_DATA");
        }
    }

    private String trendFor(UUID lifecycleId) {
        try {
            List<Double> last3 = jdbc.query(
                    "SELECT overall_score FROM intern_evaluations "
                            + " WHERE intern_lifecycle_id = ? "
                            + "   AND overall_score IS NOT NULL "
                            + "   AND status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED') "
                            + " ORDER BY published_at DESC NULLS LAST "
                            + " LIMIT 3",
                    (rs, n) -> rs.getDouble(1), lifecycleId);
            if (last3.size() < 2) return "INSUFFICIENT_DATA";
            double newest = last3.get(0);
            double oldest = last3.get(last3.size() - 1);
            double delta = newest - oldest;
            if (delta >= 0.5) return "IMPROVING";
            if (delta <= -0.5) return "DECLINING";
            return "STABLE";
        } catch (Exception e) {
            return "INSUFFICIENT_DATA";
        }
    }

    private EvaluatorDtos.I983StatusCard loadI983Card(UUID lifecycleId, UUID internUserId) {
        String planStatus = "NOT_INITIATED";
        try {
            String s = jdbc.queryForObject(
                    "SELECT status FROM i983_plans "
                            + " WHERE candidate_id IN ( "
                            + "     SELECT id FROM candidates WHERE user_id = ?) "
                            + " ORDER BY created_at DESC LIMIT 1",
                    String.class, internUserId);
            if (s != null) planStatus = s;
        } catch (Exception ignored) { /* no plan yet */ }
        Instant lastAt = null;
        String lastStatus = null;
        try {
            List<Object[]> rows = jdbc.query(
                    "SELECT published_at, status FROM i983_evaluations "
                            + " WHERE intern_lifecycle_id = ? "
                            + " ORDER BY published_at DESC NULLS LAST, created_at DESC "
                            + " LIMIT 1",
                    (rs, n) -> new Object[]{
                            rs.getTimestamp("published_at") != null
                                    ? rs.getTimestamp("published_at").toInstant() : null,
                            rs.getString("status")
                    }, lifecycleId);
            if (!rows.isEmpty()) {
                lastAt = (Instant) rows.get(0)[0];
                lastStatus = (String) rows.get(0)[1];
            }
        } catch (Exception ignored) { /* table may be empty */ }
        return new EvaluatorDtos.I983StatusCard(
                planStatus, lastAt, lastStatus, null, null);
    }

    private EvaluatorDtos.TrainerContextCard loadTrainerContext(UUID lifecycleId) {
        try {
            return jdbc.queryForObject(
                    "SELECT p.id AS project_id, p.title AS project_title, "
                            + "p.status AS project_status, p.due_date, "
                            + "wm.scheduled_for AS meeting_at, wm.status AS meeting_status, "
                            + "tu.full_name AS trainer_name "
                            + "FROM intern_lifecycles il "
                            + "LEFT JOIN users tu ON tu.id = il.trainer_id "
                            + "LEFT JOIN LATERAL ( "
                            + "    SELECT id, title, status, due_date FROM projects "
                            + "     WHERE intern_lifecycle_id = il.id "
                            + "       AND status IN ('NOT_STARTED','IN_PROGRESS','SUBMITTED','RETURNED') "
                            + "     ORDER BY due_date ASC NULLS LAST, created_at DESC "
                            + "     LIMIT 1 "
                            + ") p ON TRUE "
                            + "LEFT JOIN LATERAL ( "
                            + "    SELECT scheduled_for, status FROM weekly_meetings "
                            + "     WHERE intern_lifecycle_id = il.id "
                            + "     ORDER BY scheduled_for DESC LIMIT 1 "
                            + ") wm ON TRUE "
                            + "WHERE il.id = ?",
                    (rs, n) -> {
                        String projectId = rs.getString("project_id");
                        Instant meetingAt = rs.getTimestamp("meeting_at") != null
                                ? rs.getTimestamp("meeting_at").toInstant() : null;
                        Integer daysSince = meetingAt != null
                                ? (int) ChronoUnit.DAYS.between(meetingAt, Instant.now())
                                : null;
                        java.sql.Date due = rs.getDate("due_date");
                        return new EvaluatorDtos.TrainerContextCard(
                                projectId != null ? UUID.fromString(projectId) : null,
                                rs.getString("project_title"),
                                rs.getString("project_status"),
                                due != null ? due.toLocalDate() : null,
                                null,           // last feedback decision — defer to Phase 2
                                null,           // last feedback timestamp — defer
                                meetingAt,
                                rs.getString("meeting_status"),
                                daysSince,
                                rs.getString("trainer_name"));
                    }, lifecycleId);
        } catch (Exception e) {
            log.warn("[Evaluees.trainerContext] query failed: {}", e.getMessage());
            return new EvaluatorDtos.TrainerContextCard(
                    null, null, null, null, null, null, null, null, null, null);
        }
    }

    private List<EvaluatorDtos.EvaluationTimelineEntry> loadTimeline(UUID lifecycleId) {
        List<EvaluatorDtos.EvaluationTimelineEntry> out = new ArrayList<>();
        try {
            out.addAll(jdbc.query(
                    "SELECT id, evaluation_type, status, published_at, "
                            + "intern_acknowledged_at, overall_score, "
                            + "strengths_narrative "
                            + "FROM intern_evaluations "
                            + "WHERE intern_lifecycle_id = ? "
                            + "  AND status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED') "
                            + "ORDER BY published_at DESC NULLS LAST",
                    (rs, n) -> {
                        Object scoreObj = rs.getObject("overall_score");
                        Double overall = scoreObj != null
                                ? ((Number) scoreObj).doubleValue() : null;
                        String summary = rs.getString("strengths_narrative");
                        if (summary != null && summary.length() > 200) {
                            summary = summary.substring(0, 200);
                        }
                        return new EvaluatorDtos.EvaluationTimelineEntry(
                                UUID.fromString(rs.getString("id")),
                                "INTERN_EVALUATION",
                                rs.getString("evaluation_type"),
                                rs.getString("status"),
                                rs.getTimestamp("published_at") != null
                                        ? rs.getTimestamp("published_at").toInstant() : null,
                                rs.getTimestamp("intern_acknowledged_at") != null
                                        ? rs.getTimestamp("intern_acknowledged_at").toInstant() : null,
                                overall, summary);
                    }, lifecycleId));
        } catch (Exception e) {
            log.warn("[Evaluees.timeline] intern_evaluations query failed: {}",
                    e.getMessage());
        }
        try {
            out.addAll(jdbc.query(
                    "SELECT id, evaluation_type, status, published_at, "
                            + "acknowledged_at, training_evaluation_outcomes "
                            + "FROM i983_evaluations "
                            + "WHERE intern_lifecycle_id = ? "
                            + "ORDER BY published_at DESC NULLS LAST",
                    (rs, n) -> {
                        String summary = rs.getString("training_evaluation_outcomes");
                        if (summary != null && summary.length() > 200) {
                            summary = summary.substring(0, 200);
                        }
                        return new EvaluatorDtos.EvaluationTimelineEntry(
                                UUID.fromString(rs.getString("id")),
                                "I983_EVALUATION",
                                rs.getString("evaluation_type"),
                                rs.getString("status"),
                                rs.getTimestamp("published_at") != null
                                        ? rs.getTimestamp("published_at").toInstant() : null,
                                rs.getTimestamp("acknowledged_at") != null
                                        ? rs.getTimestamp("acknowledged_at").toInstant() : null,
                                null, summary);
                    }, lifecycleId));
        } catch (Exception e) {
            log.warn("[Evaluees.timeline] i983_evaluations query failed: {}",
                    e.getMessage());
        }
        // Merge sort newest-first across both kinds.
        out.sort((a, b) -> {
            Instant pa = a.publishedAt(), pb = b.publishedAt();
            if (pa == null && pb == null) return 0;
            if (pa == null) return 1;
            if (pb == null) return -1;
            return pb.compareTo(pa);
        });
        return out;
    }
}
