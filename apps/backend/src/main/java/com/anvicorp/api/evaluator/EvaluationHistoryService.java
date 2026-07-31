package com.anvicorp.api.evaluator;

import com.anvicorp.api.common.MonthRange;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Evaluator Phase 4 — read-only history of every evaluation this
 *  Evaluator has touched (monthly + I-983 + final), with optional
 *  filters. */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvaluationHistoryService {

    private final JdbcTemplate jdbc;

    public EvaluatorPhase4Dtos.HistoryPage list(
            User caller,
            String search,
            String type,        // MONTHLY | FINAL | POST_PROJECT | I983 | null = all
            String status,      // PUBLISHED | ACKNOWLEDGED | AMENDED | null = all
            MonthRange range,   // scopes rows by published_at within the month; null → all
            int page,
            int pageSize) {

        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, pageSize), 100);
        int offset = safePage * safeSize;

        boolean superAdmin = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);

        // Two source tables (intern_evaluations covers MONTHLY + FINAL;
        // i983_evaluations covers the I-983 branch) — UNION ALL them.
        StringBuilder data = new StringBuilder();
        StringBuilder count = new StringBuilder();
        List<Object> dataParams = new ArrayList<>();
        List<Object> countParams = new ArrayList<>();

        data.append("WITH combined AS (");
        count.append("WITH combined AS (");

        // intern_evaluations branch — POST_PROJECT gets its own entry_kind
        // so the frontend can filter/badge it distinctly from MONTHLY.
        String monthlyFinalCte = ""
                + " SELECT ev.id AS evaluation_id, "
                + "        CASE "
                + "          WHEN ev.evaluation_type = 'FINAL' THEN 'FINAL' "
                + "          WHEN ev.evaluation_type = 'POST_PROJECT' THEN 'POST_PROJECT' "
                + "          ELSE 'MONTHLY' END AS entry_kind, "
                + "        ev.intern_lifecycle_id, u.full_name AS intern_name, il.employee_id, "
                + "        ev.evaluation_type, ev.status, ev.version, "
                + "        ev.period_start, ev.period_end, ev.scheduled_for, "
                + "        ev.published_at, ev.intern_acknowledged_at AS acknowledged_at, "
                + "        ev.overall_score, ev.recommendation "
                + "   FROM intern_evaluations ev "
                + "   JOIN intern_lifecycles il ON il.id = ev.intern_lifecycle_id "
                + "   JOIN users u             ON u.id = il.user_id "
                + "  WHERE 1 = 1 ";
        // i983_evaluations branch
        String i983Cte = ""
                + " SELECT ev.id AS evaluation_id, 'I983' AS entry_kind, "
                + "        ev.intern_lifecycle_id, u.full_name AS intern_name, il.employee_id, "
                + "        ev.evaluation_type, ev.status, ev.version, "
                + "        ev.period_start_date AS period_start, ev.period_end_date AS period_end, "
                + "        NULL::timestamp AS scheduled_for, "
                + "        ev.published_at, ev.acknowledged_at, "
                + "        NULL::integer AS overall_score, "
                + "        NULL::varchar AS recommendation "
                + "   FROM i983_evaluations ev "
                + "   JOIN intern_lifecycles il ON il.id = ev.intern_lifecycle_id "
                + "   JOIN users u             ON u.id = il.user_id "
                + "  WHERE 1 = 1 ";

        StringBuilder monthlyBranch = new StringBuilder(monthlyFinalCte);
        StringBuilder i983Branch = new StringBuilder(i983Cte);
        List<Object> monthlyParams = new ArrayList<>();
        List<Object> i983Params = new ArrayList<>();

        if (!superAdmin) {
            monthlyBranch.append(" AND ev.evaluator_id = ? ");
            i983Branch.append(" AND ev.evaluator_id = ? ");
            monthlyParams.add(caller.getId());
            i983Params.add(caller.getId());
        }
        if (search != null && !search.isBlank()) {
            String like = "%" + search.trim().toLowerCase() + "%";
            monthlyBranch.append(" AND (LOWER(u.full_name) LIKE ? OR LOWER(COALESCE(il.employee_id,'')) LIKE ?) ");
            i983Branch.append(" AND (LOWER(u.full_name) LIKE ? OR LOWER(COALESCE(il.employee_id,'')) LIKE ?) ");
            monthlyParams.add(like); monthlyParams.add(like);
            i983Params.add(like); i983Params.add(like);
        }
        if (status != null && !status.isBlank()) {
            monthlyBranch.append(" AND ev.status = ? ");
            i983Branch.append(" AND ev.status = ? ");
            monthlyParams.add(status); i983Params.add(status);
        }
        // Month scoping — filter by published_at within the selected
        // month. Ordering already puts NULL-published rows at the
        // bottom, but the filter here removes them entirely from a
        // month-scoped view (unpublished drafts are not part of any
        // month's "published history" until they publish).
        if (range != null) {
            String startStr = range.startDateString();
            String endStr = range.endDateString();
            monthlyBranch.append(
                    " AND ev.published_at >= ?::timestamp "
                            + " AND ev.published_at <  ?::timestamp ");
            i983Branch.append(
                    " AND ev.published_at >= ?::timestamp "
                            + " AND ev.published_at <  ?::timestamp ");
            monthlyParams.add(startStr); monthlyParams.add(endStr);
            i983Params.add(startStr);    i983Params.add(endStr);
        }

        boolean includeMonthlyFinal = type == null || type.isBlank()
                || "MONTHLY".equals(type) || "FINAL".equals(type)
                || "POST_PROJECT".equals(type) || "ALL".equals(type);
        boolean includeI983 = type == null || type.isBlank()
                || "I983".equals(type) || "ALL".equals(type);

        if ("MONTHLY".equals(type)) {
            // Legacy MONTHLY only — exclude FINAL + POST_PROJECT explicitly.
            monthlyBranch.append(" AND ev.evaluation_type NOT IN ('FINAL','POST_PROJECT') ");
        } else if ("FINAL".equals(type)) {
            monthlyBranch.append(" AND ev.evaluation_type = 'FINAL' ");
        } else if ("POST_PROJECT".equals(type)) {
            monthlyBranch.append(" AND ev.evaluation_type = 'POST_PROJECT' ");
        }

        boolean appended = false;
        if (includeMonthlyFinal) {
            data.append(monthlyBranch);
            count.append(monthlyBranch);
            dataParams.addAll(monthlyParams);
            countParams.addAll(monthlyParams);
            appended = true;
        }
        if (includeI983) {
            if (appended) {
                data.append(" UNION ALL ");
                count.append(" UNION ALL ");
            }
            data.append(i983Branch);
            count.append(i983Branch);
            dataParams.addAll(i983Params);
            countParams.addAll(i983Params);
        }

        data.append(") SELECT * FROM combined ")
                .append(" ORDER BY COALESCE(published_at, scheduled_for) DESC NULLS LAST ")
                .append(" LIMIT ? OFFSET ?");
        dataParams.add(safeSize);
        dataParams.add(offset);

        count.append(") SELECT COUNT(*) FROM combined");

        long total;
        try {
            Long t = jdbc.queryForObject(count.toString(), Long.class, countParams.toArray());
            total = t != null ? t : 0L;
        } catch (Exception e) {
            log.warn("[EvaluationHistory] count failed: {}", e.getMessage());
            total = 0L;
        }

        List<EvaluatorPhase4Dtos.HistoryRow> rows;
        try {
            rows = jdbc.query(data.toString(),
                    (rs, n) -> {
                        Object score = rs.getObject("overall_score");
                        return new EvaluatorPhase4Dtos.HistoryRow(
                                UUID.fromString(rs.getString("evaluation_id")),
                                rs.getString("entry_kind"),
                                UUID.fromString(rs.getString("intern_lifecycle_id")),
                                rs.getString("intern_name"),
                                rs.getString("employee_id"),
                                rs.getString("evaluation_type"),
                                rs.getString("status"),
                                rs.getInt("version"),
                                rs.getDate("period_start") != null
                                        ? rs.getDate("period_start").toLocalDate() : null,
                                rs.getDate("period_end") != null
                                        ? rs.getDate("period_end").toLocalDate() : null,
                                rs.getTimestamp("scheduled_for") != null
                                        ? rs.getTimestamp("scheduled_for").toInstant() : null,
                                rs.getTimestamp("published_at") != null
                                        ? rs.getTimestamp("published_at").toInstant() : null,
                                rs.getTimestamp("acknowledged_at") != null
                                        ? rs.getTimestamp("acknowledged_at").toInstant() : null,
                                score != null ? ((Number) score).intValue() : null,
                                rs.getString("recommendation"));
                    },
                    dataParams.toArray());
        } catch (Exception e) {
            log.warn("[EvaluationHistory] data query failed: {}", e.getMessage());
            rows = List.of();
        }

        int totalPages = safeSize == 0 ? 0 : (int) Math.ceil(total / (double) safeSize);
        return new EvaluatorPhase4Dtos.HistoryPage(
                rows, safePage, safeSize, total, totalPages);
    }
}
