package com.anvicorp.api.evaluator;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.erm.reports.CsvExporter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Evaluator Phase 4 — monthly reports backing the
 *  /careers/evaluator/reports surface. KPIs + recommendation distribution
 *  + criterion averages + per-intern roll-up + CSV streaming export. */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvaluatorReportsService {

    private final JdbcTemplate jdbc;

    public EvaluatorPhase4Dtos.MonthlyReport monthly(User caller, Integer year, Integer month) {
        YearMonth ym = (year != null && month != null)
                ? YearMonth.of(year, month) : YearMonth.now();
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        boolean superAdmin = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);

        EvaluatorPhase4Dtos.ReportKpis kpis = computeKpis(caller, start, end, superAdmin);
        List<EvaluatorPhase4Dtos.RecommendationBucket> mix =
                recommendationMix(caller, start, end, superAdmin);
        EvaluatorPhase4Dtos.CriterionAverages crit =
                criterionAverages(caller, start, end, superAdmin);
        List<EvaluatorPhase4Dtos.InternRollup> rollup =
                perInternRollup(caller, start, end, superAdmin);

        String label = ym.format(DateTimeFormatter.ofPattern("MMMM yyyy"));
        return new EvaluatorPhase4Dtos.MonthlyReport(
                ym.getYear(), ym.getMonthValue(), label, kpis, mix, crit, rollup);
    }

    private EvaluatorPhase4Dtos.ReportKpis computeKpis(
            User caller, LocalDate start, LocalDate end, boolean superAdmin) {
        String scope = superAdmin ? "" : " AND ev.evaluator_id = ? ";
        List<Object> params = new ArrayList<>();
        params.add(start); params.add(end);
        if (!superAdmin) params.add(caller.getId());

        try {
            return jdbc.query(
                    "SELECT "
                            + " COUNT(*) AS total, "
                            + " COUNT(*) FILTER (WHERE status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED')) AS published, "
                            + " COUNT(*) FILTER (WHERE status = 'ACKNOWLEDGED') AS ack, "
                            + " COUNT(*) FILTER (WHERE status IN ('PUBLISHED','AMENDED') AND intern_acknowledged_at IS NULL) AS pending_ack, "
                            + " COUNT(*) FILTER (WHERE amended_at IS NOT NULL) AS amended, "
                            + " AVG(overall_score) FILTER (WHERE overall_score IS NOT NULL) AS avg_score, "
                            + " AVG(EXTRACT(EPOCH FROM (intern_acknowledged_at - published_at))/86400) "
                            + "    FILTER (WHERE intern_acknowledged_at IS NOT NULL AND published_at IS NOT NULL) AS avg_days_to_ack "
                            + "FROM intern_evaluations ev "
                            + "WHERE published_at::date BETWEEN ? AND ? " + scope,
                    rs -> {
                        if (!rs.next()) {
                            return new EvaluatorPhase4Dtos.ReportKpis(0,0,0,0,0,null,null);
                        }
                        Object avgScore = rs.getObject("avg_score");
                        Object avgAck = rs.getObject("avg_days_to_ack");
                        return new EvaluatorPhase4Dtos.ReportKpis(
                                rs.getLong("total"),
                                rs.getLong("published"),
                                rs.getLong("ack"),
                                rs.getLong("pending_ack"),
                                rs.getLong("amended"),
                                avgScore != null ? round2(((Number) avgScore).doubleValue()) : null,
                                avgAck != null ? round1(((Number) avgAck).doubleValue()) : null);
                    },
                    params.toArray());
        } catch (Exception e) {
            log.warn("[EvaluatorReports] kpi query failed: {}", e.getMessage());
            return new EvaluatorPhase4Dtos.ReportKpis(0,0,0,0,0,null,null);
        }
    }

    private List<EvaluatorPhase4Dtos.RecommendationBucket> recommendationMix(
            User caller, LocalDate start, LocalDate end, boolean superAdmin) {
        String scope = superAdmin ? "" : " AND ev.evaluator_id = ? ";
        List<Object> params = new ArrayList<>();
        params.add(start); params.add(end);
        if (!superAdmin) params.add(caller.getId());
        try {
            List<EvaluatorPhase4Dtos.RecommendationBucket> raw = jdbc.query(
                    "SELECT COALESCE(recommendation, 'UNSPECIFIED') AS rec, COUNT(*) AS cnt "
                            + "  FROM intern_evaluations ev "
                            + " WHERE published_at::date BETWEEN ? AND ? "
                            + "   AND status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED') "
                            + scope
                            + " GROUP BY recommendation "
                            + " ORDER BY cnt DESC",
                    (rs, n) -> new EvaluatorPhase4Dtos.RecommendationBucket(
                            rs.getString("rec"), rs.getLong("cnt"), 0.0),
                    params.toArray());
            long sum = raw.stream().mapToLong(EvaluatorPhase4Dtos.RecommendationBucket::count).sum();
            List<EvaluatorPhase4Dtos.RecommendationBucket> out = new ArrayList<>(raw.size());
            for (var b : raw) {
                double pct = sum == 0 ? 0.0 : round1(100.0 * b.count() / sum);
                out.add(new EvaluatorPhase4Dtos.RecommendationBucket(b.recommendation(), b.count(), pct));
            }
            return out;
        } catch (Exception e) {
            log.warn("[EvaluatorReports] rec mix query failed: {}", e.getMessage());
            return List.of();
        }
    }

    private EvaluatorPhase4Dtos.CriterionAverages criterionAverages(
            User caller, LocalDate start, LocalDate end, boolean superAdmin) {
        String scope = superAdmin ? "" : " AND ev.evaluator_id = ? ";
        List<Object> params = new ArrayList<>();
        params.add(start); params.add(end);
        if (!superAdmin) params.add(caller.getId());
        try {
            return jdbc.query(
                    "SELECT AVG(technical_skills_score) AS t, "
                            + "       AVG(communication_score) AS c, "
                            + "       AVG(professionalism_score) AS p, "
                            + "       AVG(learning_application_score) AS l "
                            + "  FROM intern_evaluations ev "
                            + " WHERE published_at::date BETWEEN ? AND ? "
                            + "   AND status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED') "
                            + scope,
                    rs -> {
                        if (!rs.next()) return new EvaluatorPhase4Dtos.CriterionAverages(null,null,null,null);
                        return new EvaluatorPhase4Dtos.CriterionAverages(
                                nullableDouble(rs.getObject("t")),
                                nullableDouble(rs.getObject("c")),
                                nullableDouble(rs.getObject("p")),
                                nullableDouble(rs.getObject("l")));
                    },
                    params.toArray());
        } catch (Exception e) {
            log.warn("[EvaluatorReports] criterion query failed: {}", e.getMessage());
            return new EvaluatorPhase4Dtos.CriterionAverages(null,null,null,null);
        }
    }

    private List<EvaluatorPhase4Dtos.InternRollup> perInternRollup(
            User caller, LocalDate start, LocalDate end, boolean superAdmin) {
        String scope = superAdmin ? "" : " AND ev.evaluator_id = ? ";
        List<Object> params = new ArrayList<>();
        params.add(start); params.add(end);
        if (!superAdmin) params.add(caller.getId());
        try {
            return jdbc.query(
                    "SELECT il.id AS lifecycle_id, u.full_name AS intern_name, il.employee_id, "
                            + "       COUNT(*) AS cnt, "
                            + "       AVG(ev.overall_score) FILTER (WHERE ev.overall_score IS NOT NULL) AS avg_score, "
                            + "       MAX(ev.published_at) AS last_published "
                            + "  FROM intern_evaluations ev "
                            + "  JOIN intern_lifecycles il ON il.id = ev.intern_lifecycle_id "
                            + "  JOIN users u             ON u.id = il.user_id "
                            + " WHERE ev.published_at::date BETWEEN ? AND ? "
                            + "   AND ev.status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED') "
                            + scope
                            + " GROUP BY il.id, u.full_name, il.employee_id "
                            + " ORDER BY u.full_name ASC",
                    (rs, n) -> {
                        Object avg = rs.getObject("avg_score");
                        return new EvaluatorPhase4Dtos.InternRollup(
                                UUID.fromString(rs.getString("lifecycle_id")),
                                rs.getString("intern_name"),
                                rs.getString("employee_id"),
                                rs.getLong("cnt"),
                                avg != null ? round2(((Number) avg).doubleValue()) : null,
                                rs.getTimestamp("last_published") != null
                                        ? rs.getTimestamp("last_published").toInstant() : null);
                    },
                    params.toArray());
        } catch (Exception e) {
            log.warn("[EvaluatorReports] rollup query failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Streams the monthly report as CSV (UTF-8 BOM + RFC 4180 escape).
     *  Body shape: KPI block, blank line, rec-mix block, blank line,
     *  criterion averages, blank line, per-intern rollup table. */
    public void streamMonthlyCsv(User caller, Integer year, Integer month, OutputStream out)
            throws IOException {
        EvaluatorPhase4Dtos.MonthlyReport r = monthly(caller, year, month);
        CsvExporter.writeBom(out);

        // Header / KPIs
        CsvExporter.writeRow(out, List.of("Evaluator Monthly Report"));
        CsvExporter.writeRow(out, List.of("Period", r.monthLabel()));
        CsvExporter.writeRow(out, List.of(""));
        CsvExporter.writeRow(out, List.of("Metric", "Value"));
        CsvExporter.writeRow(out, List.of("Total Evaluations", r.kpis().totalEvaluations()));
        CsvExporter.writeRow(out, List.of("Published", r.kpis().publishedCount()));
        CsvExporter.writeRow(out, List.of("Acknowledged", r.kpis().acknowledgedCount()));
        CsvExporter.writeRow(out, List.of("Pending Acknowledgment", r.kpis().pendingAckCount()));
        CsvExporter.writeRow(out, List.of("Amended", r.kpis().amendedCount()));
        CsvExporter.writeRow(out, List.of("Avg Overall Score",
                r.kpis().averageOverallScore() != null ? r.kpis().averageOverallScore() : ""));
        CsvExporter.writeRow(out, List.of("Avg Days to Ack",
                r.kpis().averageDaysToAck() != null ? r.kpis().averageDaysToAck() : ""));

        // Recommendation mix
        CsvExporter.writeRow(out, List.of(""));
        CsvExporter.writeRow(out, List.of("Recommendation", "Count", "Percent"));
        for (var b : r.recommendationMix()) {
            CsvExporter.writeRow(out, List.of(b.recommendation(), b.count(), b.pct() + "%"));
        }

        // Criterion averages
        CsvExporter.writeRow(out, List.of(""));
        CsvExporter.writeRow(out, List.of("Criterion", "Average"));
        CsvExporter.writeRow(out, List.of("Technical Skills",
                r.criterionAverages().technical() != null ? round2(r.criterionAverages().technical()) : ""));
        CsvExporter.writeRow(out, List.of("Communication",
                r.criterionAverages().communication() != null ? round2(r.criterionAverages().communication()) : ""));
        CsvExporter.writeRow(out, List.of("Professionalism",
                r.criterionAverages().professionalism() != null ? round2(r.criterionAverages().professionalism()) : ""));
        CsvExporter.writeRow(out, List.of("Learning Application",
                r.criterionAverages().learningApplication() != null ? round2(r.criterionAverages().learningApplication()) : ""));

        // Per-intern rollup
        CsvExporter.writeRow(out, List.of(""));
        CsvExporter.writeRow(out, List.of(
                "Intern", "Employee ID", "Evaluations", "Avg Score", "Last Published"));
        for (var it : r.perInternRollup()) {
            CsvExporter.writeRow(out, List.of(
                    it.internName() != null ? it.internName() : "",
                    it.employeeId() != null ? it.employeeId() : "",
                    it.evaluationsThisPeriod(),
                    it.averageOverallScore() != null ? it.averageOverallScore() : "",
                    it.lastPublishedAt() != null ? it.lastPublishedAt().toString() : ""));
        }
        out.flush();
    }

    private static Double nullableDouble(Object o) {
        return o == null ? null : round2(((Number) o).doubleValue());
    }

    private static Double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static Double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
