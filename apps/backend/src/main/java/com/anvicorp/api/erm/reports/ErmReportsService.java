package com.anvicorp.api.erm.reports;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.erm.ReasonCode;
import com.anvicorp.api.erm.reports.ErmReportsDtos.*;
import com.anvicorp.api.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ERM Phase 7 — 7 on-demand reports. Computed via direct SQL; no
 * materialized views in this phase. Date range is mandatory (defaults
 * applied at controller level); max 365-day span enforced here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErmReportsService {

    private static final int MAX_RANGE_DAYS = 365;

    /** 12 funnel stages — REGISTERED is implicit (anyone in users); the
     *  pipeline starts at EMAIL_VERIFIED and ends at INACTIVE_INTERN. */
    private static final List<String> FUNNEL_STAGES = List.of(
            "REGISTERED",
            "EMAIL_VERIFIED",
            "APPLICATION_SUBMITTED",
            "SHORTLISTED",
            "INTERVIEW_SCHEDULED",
            "INTERVIEW_COMPLETED",
            "OFFER_SENT",
            "OFFER_SIGNED",
            "EMPLOYEE_ID_CREATED",
            "ONBOARDING_ACCEPTED",
            "ACTIVE_INTERN",
            "INACTIVE_INTERN");

    private final JdbcTemplate jdbc;

    // ── 1. Pipeline funnel ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PipelineFunnelData pipelineFunnel(ReportFilters f, User caller) {
        ReportFilters filters = normalise(f);
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (String stage : FUNNEL_STAGES) counts.put(stage, 0L);

        // We count users whose CURRENT lifecycle_status is *at or beyond*
        // each stage, since lifecycle_status is monotonic. Funnel
        // interpretation: "made it to ≥ X". Users created within range only.
        StringBuilder sql = new StringBuilder(
                "SELECT lifecycle_status AS s, COUNT(*) AS c "
                        + "  FROM users "
                        + " WHERE created_at BETWEEN ? AND ? "
                        + " GROUP BY lifecycle_status");
        try {
            for (Map<String, Object> row : jdbc.queryForList(sql.toString(),
                    java.sql.Timestamp.valueOf(filters.from().atStartOfDay()),
                    java.sql.Timestamp.valueOf(filters.to().plusDays(1).atStartOfDay()))) {
                String stage = String.valueOf(row.get("s"));
                long c = ((Number) row.getOrDefault("c", 0L)).longValue();
                counts.merge(stage, c, Long::sum);
            }
        } catch (Exception e) {
            log.warn("[Reports] pipeline funnel grouping failed (non-fatal): {}",
                    e.getMessage());
        }
        // Cumulate from the bottom up so "≥ stage" is reflected.
        long running = 0;
        Map<String, Long> atOrBeyond = new java.util.LinkedHashMap<>();
        List<String> reversed = new ArrayList<>(FUNNEL_STAGES);
        java.util.Collections.reverse(reversed);
        for (String stage : reversed) {
            running += counts.getOrDefault(stage, 0L);
            atOrBeyond.put(stage, running);
        }

        List<FunnelStage> out = new ArrayList<>();
        Long previous = null;
        for (String stage : FUNNEL_STAGES) {
            long n = atOrBeyond.getOrDefault(stage, 0L);
            Double conv = previous == null || previous == 0L ? null
                    : (n * 100.0 / previous);
            out.add(new FunnelStage(stage, n, conv, null));
            previous = n;
        }
        long uniqueApplicants = atOrBeyond.getOrDefault("APPLICATION_SUBMITTED", 0L);
        return new PipelineFunnelData(filters.from(), filters.to(), out, uniqueApplicants);
    }

    // ── 2. Time-to-hire ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public TimeToHireData timeToHire(ReportFilters f, User caller) {
        ReportFilters filters = normalise(f);
        List<Double> daysList = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT EXTRACT(EPOCH FROM (o.signed_at - a.created_at))/86400 AS d "
                            + "  FROM offers o "
                            + "  JOIN applications a ON a.id = o.application_id "
                            + " WHERE o.signed_at IS NOT NULL "
                            + "   AND o.signed_at BETWEEN ? AND ?",
                    java.sql.Timestamp.valueOf(filters.from().atStartOfDay()),
                    java.sql.Timestamp.valueOf(filters.to().plusDays(1).atStartOfDay()));
            for (var r : rows) {
                Object d = r.get("d");
                if (d instanceof Number num) daysList.add(num.doubleValue());
            }
        } catch (Exception e) {
            log.warn("[Reports] time-to-hire base query failed: {}", e.getMessage());
        }
        Double avg = mean(daysList);
        Double median = percentile(daysList, 50);
        Double p90 = percentile(daysList, 90);

        // Bucket by job_type (offers join applications join job_postings)
        List<TimeToHireBucket> byJobType = new ArrayList<>();
        try {
            for (var r : jdbc.queryForList(
                    "SELECT jp.employment_type AS jt, "
                            + "       COUNT(*) AS c, "
                            + "       AVG(EXTRACT(EPOCH FROM (o.signed_at - a.created_at))/86400) AS d "
                            + "  FROM offers o "
                            + "  JOIN applications a ON a.id = o.application_id "
                            + "  LEFT JOIN job_postings jp ON jp.id = a.job_posting_id "
                            + " WHERE o.signed_at IS NOT NULL "
                            + "   AND o.signed_at BETWEEN ? AND ? "
                            + " GROUP BY jp.employment_type",
                    java.sql.Timestamp.valueOf(filters.from().atStartOfDay()),
                    java.sql.Timestamp.valueOf(filters.to().plusDays(1).atStartOfDay()))) {
                String label = String.valueOf(r.getOrDefault("jt", "UNKNOWN"));
                long count = ((Number) r.getOrDefault("c", 0L)).longValue();
                Double d = r.get("d") != null ? ((Number) r.get("d")).doubleValue() : null;
                byJobType.add(new TimeToHireBucket(label, count, d, null, null));
            }
        } catch (Exception e) {
            log.warn("[Reports] time-to-hire byJobType failed: {}", e.getMessage());
        }

        // Bucket by signed-month
        List<TimeToHireBucket> byMonth = new ArrayList<>();
        try {
            for (var r : jdbc.queryForList(
                    "SELECT TO_CHAR(date_trunc('month', o.signed_at), 'YYYY-MM') AS m, "
                            + "       COUNT(*) AS c, "
                            + "       AVG(EXTRACT(EPOCH FROM (o.signed_at - a.created_at))/86400) AS d "
                            + "  FROM offers o "
                            + "  JOIN applications a ON a.id = o.application_id "
                            + " WHERE o.signed_at IS NOT NULL "
                            + "   AND o.signed_at BETWEEN ? AND ? "
                            + " GROUP BY date_trunc('month', o.signed_at) "
                            + " ORDER BY date_trunc('month', o.signed_at) ASC",
                    java.sql.Timestamp.valueOf(filters.from().atStartOfDay()),
                    java.sql.Timestamp.valueOf(filters.to().plusDays(1).atStartOfDay()))) {
                byMonth.add(new TimeToHireBucket(
                        String.valueOf(r.get("m")),
                        ((Number) r.getOrDefault("c", 0L)).longValue(),
                        r.get("d") != null ? ((Number) r.get("d")).doubleValue() : null,
                        null, null));
            }
        } catch (Exception e) {
            log.warn("[Reports] time-to-hire byMonth failed: {}", e.getMessage());
        }

        return new TimeToHireData(filters.from(), filters.to(),
                avg, median, p90, daysList.size(), byJobType, byMonth);
    }

    // ── 3. Application decision funnel ───────────────────────────────────

    @Transactional(readOnly = true)
    public DecisionFunnelData decisionFunnel(ReportFilters f, User caller) {
        ReportFilters filters = normalise(f);
        long total = 0;
        List<DecisionSlice> decisions = new ArrayList<>();
        try {
            for (var r : jdbc.queryForList(
                    "SELECT status AS d, COUNT(*) AS c "
                            + "  FROM applications "
                            + " WHERE updated_at BETWEEN ? AND ? "
                            + "   AND status IN ('SHORTLISTED','REJECTED','HOLD','APPLIED','INTERVIEWED','SELECTED_CONDITIONAL','OFFERED','ACCEPTED','WITHDRAWN','LAPSED','NO_SHOW') "
                            + " GROUP BY status",
                    java.sql.Timestamp.valueOf(filters.from().atStartOfDay()),
                    java.sql.Timestamp.valueOf(filters.to().plusDays(1).atStartOfDay()))) {
                long c = ((Number) r.getOrDefault("c", 0L)).longValue();
                total += c;
                decisions.add(new DecisionSlice(String.valueOf(r.get("d")), c, null));
            }
        } catch (Exception e) {
            log.warn("[Reports] decision funnel base failed: {}", e.getMessage());
        }
        final long denom = total;
        decisions = decisions.stream()
                .map(d -> new DecisionSlice(d.decision(), d.count(),
                        denom == 0 ? 0.0 : d.count() * 100.0 / denom))
                .toList();

        // Top reason codes from application_decision_logs (Phase 2).
        List<ReasonCount> reasons = new ArrayList<>();
        try {
            for (var r : jdbc.queryForList(
                    "SELECT reason_code AS rc, COUNT(*) AS c "
                            + "  FROM application_decision_logs "
                            + " WHERE created_at BETWEEN ? AND ? "
                            + "   AND reason_code IS NOT NULL "
                            + " GROUP BY reason_code "
                            + " ORDER BY COUNT(*) DESC LIMIT 10",
                    java.sql.Timestamp.valueOf(filters.from().atStartOfDay()),
                    java.sql.Timestamp.valueOf(filters.to().plusDays(1).atStartOfDay()))) {
                String code = String.valueOf(r.get("rc"));
                long c = ((Number) r.getOrDefault("c", 0L)).longValue();
                reasons.add(new ReasonCount(code, humanLabel(code), c));
            }
        } catch (Exception e) {
            log.debug("[Reports] decision funnel reasons failed (non-fatal): {}",
                    e.getMessage());
        }
        return new DecisionFunnelData(filters.from(), filters.to(),
                total, decisions, reasons);
    }

    // ── 4. Completion rate ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CompletionRateData completionRate(ReportFilters f, User caller) {
        ReportFilters filters = normalise(f);
        Map<String, Long> totals = new java.util.LinkedHashMap<>();
        try {
            for (var r : jdbc.queryForList(
                    "SELECT COALESCE(er.exit_type, 'IN_PROGRESS') AS et, COUNT(*) AS c "
                            + "  FROM intern_lifecycles il "
                            + "  LEFT JOIN exit_records er ON er.intern_lifecycle_id = il.id "
                            + " WHERE il.started_at BETWEEN ? AND ? "
                            + " GROUP BY COALESCE(er.exit_type, 'IN_PROGRESS')",
                    java.sql.Timestamp.valueOf(filters.from().atStartOfDay()),
                    java.sql.Timestamp.valueOf(filters.to().plusDays(1).atStartOfDay()))) {
                totals.put(String.valueOf(r.get("et")),
                        ((Number) r.getOrDefault("c", 0L)).longValue());
            }
        } catch (Exception e) {
            log.warn("[Reports] completion rate totals failed: {}", e.getMessage());
        }
        long totActivated = totals.values().stream().mapToLong(Long::longValue).sum();
        long totCompleted = totals.getOrDefault("COMPLETED", 0L);
        long totResigned = totals.getOrDefault("RESIGNED", 0L);
        long totTerminated = totals.getOrDefault("TERMINATED", 0L);
        long totInProgress = totals.getOrDefault("IN_PROGRESS", 0L);

        List<CompletionBucket> byTrainer = bucketBy("trainer", "trainer_id", filters);
        List<CompletionBucket> byEvaluator = bucketBy("evaluator", "evaluator_id", filters);
        List<CompletionBucket> byManager = bucketBy("manager", "manager_id", filters);

        return new CompletionRateData(filters.from(), filters.to(),
                totActivated, totCompleted, totResigned, totTerminated, totInProgress,
                byTrainer, byEvaluator, byManager);
    }

    private List<CompletionBucket> bucketBy(String roleLabel,
                                             String column,
                                             ReportFilters filters) {
        List<CompletionBucket> out = new ArrayList<>();
        try {
            String sql = "SELECT il." + column + " AS mid, u.full_name AS mname, "
                    + "       COUNT(*) AS activated, "
                    + "       COUNT(*) FILTER (WHERE er.exit_type = 'COMPLETED') AS completed, "
                    + "       COUNT(*) FILTER (WHERE er.exit_type = 'RESIGNED') AS resigned, "
                    + "       COUNT(*) FILTER (WHERE er.exit_type = 'TERMINATED') AS terminated, "
                    + "       COUNT(*) FILTER (WHERE er.exit_type IS NULL) AS inprog "
                    + "  FROM intern_lifecycles il "
                    + "  LEFT JOIN exit_records er ON er.intern_lifecycle_id = il.id "
                    + "  LEFT JOIN users u ON u.id = il." + column
                    + " WHERE il.started_at BETWEEN ? AND ? "
                    + "   AND il." + column + " IS NOT NULL "
                    + " GROUP BY il." + column + ", u.full_name "
                    + " ORDER BY activated DESC LIMIT 25";
            for (var r : jdbc.queryForList(sql,
                    java.sql.Timestamp.valueOf(filters.from().atStartOfDay()),
                    java.sql.Timestamp.valueOf(filters.to().plusDays(1).atStartOfDay()))) {
                out.add(new CompletionBucket(
                        roleLabel,
                        r.get("mid") != null ? UUID.fromString(String.valueOf(r.get("mid"))) : null,
                        (String) r.get("mname"),
                        ((Number) r.getOrDefault("activated", 0L)).longValue(),
                        ((Number) r.getOrDefault("completed", 0L)).longValue(),
                        ((Number) r.getOrDefault("resigned", 0L)).longValue(),
                        ((Number) r.getOrDefault("terminated", 0L)).longValue(),
                        ((Number) r.getOrDefault("inprog", 0L)).longValue()));
            }
        } catch (Exception e) {
            log.warn("[Reports] completion bucketBy {} failed: {}", roleLabel, e.getMessage());
        }
        return out;
    }

    // ── 5. Attrition ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public AttritionData attrition(ReportFilters f, User caller) {
        ReportFilters filters = normalise(f);
        long total = 0;
        List<AttritionByType> byType = new ArrayList<>();
        try {
            for (var r : jdbc.queryForList(
                    "SELECT exit_type AS et, COUNT(*) AS c "
                            + "  FROM exit_records "
                            + " WHERE exit_date BETWEEN ? AND ? "
                            + " GROUP BY exit_type",
                    filters.from(), filters.to())) {
                long c = ((Number) r.getOrDefault("c", 0L)).longValue();
                total += c;
                byType.add(new AttritionByType(String.valueOf(r.get("et")), c, null));
            }
        } catch (Exception e) {
            log.warn("[Reports] attrition base failed: {}", e.getMessage());
        }
        final long denom = total;
        byType = byType.stream()
                .map(a -> new AttritionByType(a.exitType(), a.count(),
                        denom == 0 ? 0.0 : a.count() * 100.0 / denom))
                .toList();

        List<ReasonCount> reasons = new ArrayList<>();
        try {
            for (var r : jdbc.queryForList(
                    "SELECT reason_code AS rc, COUNT(*) AS c "
                            + "  FROM exit_records "
                            + " WHERE exit_date BETWEEN ? AND ? "
                            + "   AND reason_code IS NOT NULL "
                            + " GROUP BY reason_code "
                            + " ORDER BY COUNT(*) DESC LIMIT 10",
                    filters.from(), filters.to())) {
                String code = String.valueOf(r.get("rc"));
                long c = ((Number) r.getOrDefault("c", 0L)).longValue();
                reasons.add(new ReasonCount(code, humanLabel(code), c));
            }
        } catch (Exception e) {
            log.debug("[Reports] attrition reasons failed (non-fatal): {}",
                    e.getMessage());
        }
        return new AttritionData(filters.from(), filters.to(),
                total, byType, reasons);
    }

    // ── 6. Evaluation distribution ───────────────────────────────────────

    @Transactional(readOnly = true)
    public EvaluationDistributionData evaluationDistribution(ReportFilters f, User caller) {
        ReportFilters filters = normalise(f);
        long total = 0;
        Double avg = null;
        long[] hist = new long[11]; // 0-10 (we'll surface 1-10)
        try {
            for (var r : jdbc.queryForList(
                    "SELECT COALESCE(overall_score, 0) AS s, COUNT(*) AS c "
                            + "  FROM intern_evaluations "
                            + " WHERE evaluation_type = 'MONTHLY' "
                            + "   AND status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED') "
                            + "   AND published_at BETWEEN ? AND ? "
                            + " GROUP BY COALESCE(overall_score, 0)",
                    java.sql.Timestamp.valueOf(filters.from().atStartOfDay()),
                    java.sql.Timestamp.valueOf(filters.to().plusDays(1).atStartOfDay()))) {
                int s = ((Number) r.getOrDefault("s", 0)).intValue();
                long c = ((Number) r.getOrDefault("c", 0L)).longValue();
                if (s >= 0 && s <= 10) hist[s] += c;
                total += c;
            }
        } catch (Exception e) {
            log.warn("[Reports] evaluation histogram failed: {}", e.getMessage());
        }
        if (total > 0) {
            long weighted = 0;
            for (int s = 1; s <= 10; s++) weighted += (long) s * hist[s];
            avg = weighted * 1.0 / total;
        }
        List<ScoreBucket> histogram = new ArrayList<>();
        for (int s = 1; s <= 10; s++) histogram.add(new ScoreBucket(s, hist[s]));

        List<EvaluatorBucket> byEvaluator = new ArrayList<>();
        try {
            for (var r : jdbc.queryForList(
                    "SELECT ie.evaluator_id AS eid, u.full_name AS ename, "
                            + "       COUNT(*) AS c, AVG(COALESCE(ie.overall_score, 0)) AS avg "
                            + "  FROM intern_evaluations ie "
                            + "  LEFT JOIN users u ON u.id = ie.evaluator_id "
                            + " WHERE ie.evaluation_type = 'MONTHLY' "
                            + "   AND ie.status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED') "
                            + "   AND ie.published_at BETWEEN ? AND ? "
                            + " GROUP BY ie.evaluator_id, u.full_name "
                            + " ORDER BY c DESC LIMIT 25",
                    java.sql.Timestamp.valueOf(filters.from().atStartOfDay()),
                    java.sql.Timestamp.valueOf(filters.to().plusDays(1).atStartOfDay()))) {
                byEvaluator.add(new EvaluatorBucket(
                        r.get("eid") != null ? UUID.fromString(String.valueOf(r.get("eid"))) : null,
                        (String) r.get("ename"),
                        ((Number) r.getOrDefault("c", 0L)).longValue(),
                        r.get("avg") != null ? ((Number) r.get("avg")).doubleValue() : null));
            }
        } catch (Exception e) {
            log.warn("[Reports] evaluator bucket failed: {}", e.getMessage());
        }
        return new EvaluationDistributionData(filters.from(), filters.to(),
                total, avg, histogram, byEvaluator);
    }

    // ── 7. Timesheet compliance ──────────────────────────────────────────

    @Transactional(readOnly = true)
    public TimesheetComplianceData timesheetCompliance(ReportFilters f, User caller) {
        ReportFilters filters = normalise(f);
        long totalWeeks = 0;
        long onTime = 0;
        long firstTryApproved = 0;
        List<InternTimesheetCompliance> perIntern = new ArrayList<>();
        try {
            for (var r : jdbc.queryForList(
                    "SELECT c.user_id AS uid, u.full_name AS uname, "
                            + "       COUNT(t.id) AS tracked, "
                            + "       COUNT(*) FILTER (WHERE t.status IN ('SUBMITTED','APPROVED')) AS submitted_or_approved, "
                            + "       COUNT(*) FILTER (WHERE t.status = 'APPROVED') AS approved, "
                            + "       COUNT(*) FILTER (WHERE t.status = 'REJECTED') AS rejected "
                            + "  FROM timesheets t "
                            + "  JOIN candidates c ON c.id = t.intern_id "
                            + "  JOIN users u ON u.id = c.user_id "
                            + " WHERE t.week_start BETWEEN ? AND ? "
                            + " GROUP BY c.user_id, u.full_name "
                            + " ORDER BY u.full_name ASC",
                    filters.from(), filters.to())) {
                long tracked = ((Number) r.getOrDefault("tracked", 0L)).longValue();
                long subOk = ((Number) r.getOrDefault("submitted_or_approved", 0L)).longValue();
                long approved = ((Number) r.getOrDefault("approved", 0L)).longValue();
                long rejected = ((Number) r.getOrDefault("rejected", 0L)).longValue();
                totalWeeks += tracked;
                onTime += subOk;
                firstTryApproved += (approved - rejected > 0 ? approved - rejected : 0);
                Double onTimePct = tracked == 0 ? 0.0 : subOk * 100.0 / tracked;
                Double ftPct = tracked == 0 ? 0.0
                        : Math.max(0, approved - rejected) * 100.0 / tracked;
                perIntern.add(new InternTimesheetCompliance(
                        r.get("uid") != null ? UUID.fromString(String.valueOf(r.get("uid"))) : null,
                        (String) r.get("uname"),
                        tracked, subOk, Math.max(0, approved - rejected), rejected,
                        onTimePct, ftPct));
            }
        } catch (Exception e) {
            log.warn("[Reports] timesheet compliance failed: {}", e.getMessage());
        }
        Double aggOnTime = totalWeeks == 0 ? 0.0 : onTime * 100.0 / totalWeeks;
        Double aggFirstTry = totalWeeks == 0 ? 0.0
                : firstTryApproved * 100.0 / totalWeeks;
        return new TimesheetComplianceData(filters.from(), filters.to(),
                totalWeeks, aggOnTime, aggFirstTry, perIntern);
    }

    // ── CSV dispatch ─────────────────────────────────────────────────────

    public void exportCsv(String reportType, ReportFilters filters,
                           User caller, OutputStream out) throws IOException {
        CsvExporter.writeBom(out);
        switch (reportType.toLowerCase()) {
            case "pipeline-funnel" -> writePipeline(pipelineFunnel(filters, caller), out);
            case "time-to-hire" -> writeTimeToHire(timeToHire(filters, caller), out);
            case "decision-funnel" -> writeDecisionFunnel(decisionFunnel(filters, caller), out);
            case "completion-rate" -> writeCompletion(completionRate(filters, caller), out);
            case "attrition" -> writeAttrition(attrition(filters, caller), out);
            case "evaluation-distribution" ->
                    writeEvaluationDistribution(evaluationDistribution(filters, caller), out);
            case "timesheet-compliance" ->
                    writeTimesheetCompliance(timesheetCompliance(filters, caller), out);
            default -> throw new BadRequestException(
                    "Unknown report type: " + reportType);
        }
    }

    private void writePipeline(PipelineFunnelData d, OutputStream out) throws IOException {
        CsvExporter.writeRow(out, List.of("Stage", "Count", "ConversionFromPrev%"));
        for (FunnelStage s : d.stages()) {
            CsvExporter.writeRow(out, List.of(
                    s.stage(), s.count(),
                    s.conversionFromPrevious() == null ? "" :
                            String.format("%.1f", s.conversionFromPrevious())));
        }
    }

    private void writeTimeToHire(TimeToHireData d, OutputStream out) throws IOException {
        CsvExporter.writeRow(out, List.of("Bucket", "Label", "Count", "AvgDays"));
        for (TimeToHireBucket b : d.byJobType()) {
            CsvExporter.writeRow(out, List.of("JobType", b.label(), b.count(),
                    b.avgDays() == null ? "" : String.format("%.1f", b.avgDays())));
        }
        for (TimeToHireBucket b : d.byMonth()) {
            CsvExporter.writeRow(out, List.of("Month", b.label(), b.count(),
                    b.avgDays() == null ? "" : String.format("%.1f", b.avgDays())));
        }
    }

    private void writeDecisionFunnel(DecisionFunnelData d, OutputStream out) throws IOException {
        CsvExporter.writeRow(out, List.of("Decision", "Count", "Pct"));
        for (DecisionSlice s : d.decisions()) {
            CsvExporter.writeRow(out, List.of(s.decision(), s.count(),
                    s.pct() == null ? "" : String.format("%.1f", s.pct())));
        }
        CsvExporter.writeRow(out, List.of(""));
        CsvExporter.writeRow(out, List.of("ReasonCode", "Label", "Count"));
        for (ReasonCount r : d.topReasons()) {
            CsvExporter.writeRow(out, List.of(r.reasonCode(), r.humanLabel(), r.count()));
        }
    }

    private void writeCompletion(CompletionRateData d, OutputStream out) throws IOException {
        CsvExporter.writeRow(out, List.of("Role", "MentorId", "MentorName",
                "Activated", "Completed", "Resigned", "Terminated", "InProgress"));
        for (var bs : List.of(d.byTrainer(), d.byEvaluator(), d.byManager())) {
            for (CompletionBucket b : bs) {
                CsvExporter.writeRow(out, List.of(
                        b.mentorRole(),
                        b.mentorId() == null ? "" : b.mentorId().toString(),
                        b.mentorName(), b.activated(), b.completed(),
                        b.resigned(), b.terminated(), b.inProgress()));
            }
        }
    }

    private void writeAttrition(AttritionData d, OutputStream out) throws IOException {
        CsvExporter.writeRow(out, List.of("ExitType", "Count", "Pct"));
        for (AttritionByType b : d.byType()) {
            CsvExporter.writeRow(out, List.of(b.exitType(), b.count(),
                    b.pct() == null ? "" : String.format("%.1f", b.pct())));
        }
        CsvExporter.writeRow(out, List.of(""));
        CsvExporter.writeRow(out, List.of("ReasonCode", "Label", "Count"));
        for (ReasonCount r : d.topReasons()) {
            CsvExporter.writeRow(out, List.of(r.reasonCode(), r.humanLabel(), r.count()));
        }
    }

    private void writeEvaluationDistribution(EvaluationDistributionData d,
                                              OutputStream out) throws IOException {
        CsvExporter.writeRow(out, List.of("Score", "Count"));
        for (ScoreBucket b : d.histogram()) {
            CsvExporter.writeRow(out, List.of(b.score(), b.count()));
        }
        CsvExporter.writeRow(out, List.of(""));
        CsvExporter.writeRow(out, List.of("EvaluatorId", "EvaluatorName", "Count", "AvgScore"));
        for (EvaluatorBucket b : d.byEvaluator()) {
            CsvExporter.writeRow(out, List.of(
                    b.evaluatorId() == null ? "" : b.evaluatorId().toString(),
                    b.evaluatorName(), b.evaluations(),
                    b.avgScore() == null ? "" : String.format("%.2f", b.avgScore())));
        }
    }

    private void writeTimesheetCompliance(TimesheetComplianceData d,
                                           OutputStream out) throws IOException {
        CsvExporter.writeRow(out, List.of("InternId", "InternName", "WeeksTracked",
                "OnTimeSubmitted", "ApprovedFirstTry", "EverRejected",
                "OnTimePct", "FirstTryPct"));
        for (InternTimesheetCompliance b : d.perIntern()) {
            CsvExporter.writeRow(out, List.of(
                    b.internUserId() == null ? "" : b.internUserId().toString(),
                    b.internName(), b.weeksTracked(),
                    b.onTimeSubmitted(), b.approvedFirstTry(), b.everRejected(),
                    b.onTimePct() == null ? "" : String.format("%.1f", b.onTimePct()),
                    b.firstTryPct() == null ? "" : String.format("%.1f", b.firstTryPct())));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private ReportFilters normalise(ReportFilters f) {
        LocalDate to = f != null && f.to() != null ? f.to() : LocalDate.now();
        LocalDate from = f != null && f.from() != null ? f.from() : to.minusDays(90);
        if (from.isAfter(to)) throw new BadRequestException("from must be ≤ to");
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new BadRequestException(
                    "date range exceeds max " + MAX_RANGE_DAYS + " days");
        }
        return new ReportFilters(from, to,
                f != null ? f.jobType() : null,
                f != null ? f.jobId() : null,
                f != null ? f.ermOwnerId() : null,
                f != null ? f.trainerId() : null,
                f != null ? f.evaluatorId() : null,
                f != null ? f.managerId() : null,
                f != null ? f.scope() : "all");
    }

    private static Double mean(List<Double> xs) {
        if (xs.isEmpty()) return null;
        double sum = 0;
        for (Double d : xs) sum += d;
        return sum / xs.size();
    }

    private static Double percentile(List<Double> xs, int pct) {
        if (xs.isEmpty()) return null;
        List<Double> sorted = new ArrayList<>(xs);
        java.util.Collections.sort(sorted);
        int idx = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        idx = Math.max(0, Math.min(sorted.size() - 1, idx));
        return sorted.get(idx);
    }

    private static String humanLabel(String code) {
        if (code == null) return null;
        try { return ReasonCode.valueOf(code).humanLabel(); }
        catch (Exception e) {
            return code.replace('_', ' ').toLowerCase();
        }
    }
}
