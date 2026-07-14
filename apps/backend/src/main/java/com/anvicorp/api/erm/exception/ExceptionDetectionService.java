package com.anvicorp.api.erm.exception;

import com.anvicorp.api.erm.dashboard.ErmScope;
import com.anvicorp.api.erm.dashboard.ErmThresholds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 1 — compute-on-demand exception detector for the ERM Home
 * dashboard. Eight independent SQL counts + a small top-urgent union
 * keep the call cheap (each query indexed per
 * {@link com.anvicorp.api.bootstrap.SchemaFixupRunner}). Phase 6 will
 * add a scheduled job that persists rows into a dedicated table; for
 * Phase 1 the service is read-only and idempotent.
 *
 * <p>All queries operate at the SQL layer — no in-memory scanning of
 * large lists. JdbcTemplate is used instead of repositories so the
 * cross-entity joins stay clean and the column names match what the
 * existing schema actually carries.</p>
 *
 * <p>Severity assignment matches the doc: timing-risk and overdue-offer
 * are URGENT; document/project/meeting/evaluation/exit are WARN; the
 * timesheet flag is INFO.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExceptionDetectionService {

    /** Per-type severity, declared once so the mapping stays consistent. */
    private static final Map<ExceptionType, ExceptionSeverity> SEVERITY =
            new EnumMap<>(ExceptionType.class);
    static {
        SEVERITY.put(ExceptionType.UNSIGNED_OFFER_OVERDUE,  ExceptionSeverity.URGENT);
        SEVERITY.put(ExceptionType.ONBOARDING_DOC_REJECTED, ExceptionSeverity.WARN);
        SEVERITY.put(ExceptionType.I9_EVERIFY_TIMING_RISK,  ExceptionSeverity.URGENT);
        SEVERITY.put(ExceptionType.NO_PROJECT_ASSIGNED,     ExceptionSeverity.WARN);
        SEVERITY.put(ExceptionType.TRAINER_MEETING_MISSING, ExceptionSeverity.WARN);
        SEVERITY.put(ExceptionType.EVALUATION_OVERDUE,      ExceptionSeverity.WARN);
        SEVERITY.put(ExceptionType.TIMESHEET_MISSING,       ExceptionSeverity.INFO);
        SEVERITY.put(ExceptionType.EXIT_CHECKLIST_PENDING,  ExceptionSeverity.WARN);
        SEVERITY.put(ExceptionType.REPORTING_STRUCTURE_INCOMPLETE,
                ExceptionSeverity.URGENT);
        SEVERITY.put(ExceptionType.WORK_AUTH_EXPIRING_30,   ExceptionSeverity.URGENT);
        SEVERITY.put(ExceptionType.I983_EVALUATION_OVERDUE, ExceptionSeverity.WARN);
        SEVERITY.put(ExceptionType.EVERIFY_NONCONFIRMATION, ExceptionSeverity.URGENT);
        SEVERITY.put(ExceptionType.MISSED_TRAINER_MEETING,        ExceptionSeverity.WARN);
        SEVERITY.put(ExceptionType.LOW_PROJECT_PROGRESS,          ExceptionSeverity.WARN);
        SEVERITY.put(ExceptionType.REPEATED_TIMESHEET_REJECTION,  ExceptionSeverity.URGENT);
        SEVERITY.put(ExceptionType.EXIT_OVERDUE,                  ExceptionSeverity.WARN);
        // Trainer Phase 0 — detector method registered in Phase 3 alongside
        // the ESCALATE review flow. Severity slot reserved so the
        // dashboard + escalations queue render the row correctly when it
        // starts being produced.
        SEVERITY.put(ExceptionType.TRAINER_ESCALATION,            ExceptionSeverity.URGENT);
    }

    private static final int TOP_URGENT_LIMIT = 5;

    private final JdbcTemplate jdbc;

    public ExceptionDetectionResult detect(ErmScope scope, UUID callerId) {
        Map<ExceptionType, Integer> counts = new EnumMap<>(ExceptionType.class);
        List<ExceptionRow> all = new ArrayList<>();

        runDetector(ExceptionType.UNSIGNED_OFFER_OVERDUE,
                this::unsignedOfferOverdue, scope, callerId, counts, all);
        runDetector(ExceptionType.ONBOARDING_DOC_REJECTED,
                this::onboardingDocRejected, scope, callerId, counts, all);
        runDetector(ExceptionType.I9_EVERIFY_TIMING_RISK,
                this::i9EverifyTimingRisk, scope, callerId, counts, all);
        runDetector(ExceptionType.NO_PROJECT_ASSIGNED,
                this::noProjectAssigned, scope, callerId, counts, all);
        runDetector(ExceptionType.TRAINER_MEETING_MISSING,
                this::trainerMeetingMissing, scope, callerId, counts, all);
        runDetector(ExceptionType.EVALUATION_OVERDUE,
                this::evaluationOverdue, scope, callerId, counts, all);
        runDetector(ExceptionType.TIMESHEET_MISSING,
                this::timesheetMissing, scope, callerId, counts, all);
        runDetector(ExceptionType.EXIT_CHECKLIST_PENDING,
                this::exitChecklistPending, scope, callerId, counts, all);
        runDetector(ExceptionType.REPORTING_STRUCTURE_INCOMPLETE,
                this::reportingStructureIncomplete, scope, callerId, counts, all);
        runDetector(ExceptionType.WORK_AUTH_EXPIRING_30,
                this::workAuthExpiring30, scope, callerId, counts, all);
        runDetector(ExceptionType.I983_EVALUATION_OVERDUE,
                this::i983EvaluationOverdue, scope, callerId, counts, all);
        runDetector(ExceptionType.EVERIFY_NONCONFIRMATION,
                this::everifyNonconfirmation, scope, callerId, counts, all);
        runDetector(ExceptionType.MISSED_TRAINER_MEETING,
                this::missedTrainerMeeting, scope, callerId, counts, all);
        runDetector(ExceptionType.LOW_PROJECT_PROGRESS,
                this::lowProjectProgress, scope, callerId, counts, all);
        runDetector(ExceptionType.REPEATED_TIMESHEET_REJECTION,
                this::repeatedTimesheetRejection, scope, callerId, counts, all);
        runDetector(ExceptionType.EXIT_OVERDUE,
                this::exitOverdue, scope, callerId, counts, all);

        // Guarantee every enum value is keyed (zeros for empty detectors).
        for (ExceptionType t : ExceptionType.values()) {
            counts.putIfAbsent(t, 0);
        }

        List<ExceptionRow> topUrgent = all.stream()
                .sorted(Comparator
                        .comparing((ExceptionRow r) -> severityRank(r.severity()))
                        .thenComparing(Comparator.comparingInt(ExceptionRow::daysOverdue).reversed()))
                .limit(TOP_URGENT_LIMIT)
                .toList();

        return new ExceptionDetectionResult(counts, topUrgent);
    }

    private void runDetector(ExceptionType type,
                              Detector detector,
                              ErmScope scope,
                              UUID callerId,
                              Map<ExceptionType, Integer> counts,
                              List<ExceptionRow> all) {
        try {
            List<ExceptionRow> rows = detector.run(scope, callerId);
            counts.put(type, rows.size());
            all.addAll(rows);
        } catch (Exception e) {
            log.warn("[ExceptionDetection] {} failed (non-fatal): {}", type, e.getMessage());
            counts.putIfAbsent(type, 0);
        }
    }

    @FunctionalInterface
    private interface Detector {
        List<ExceptionRow> run(ErmScope scope, UUID callerId);
    }

    private static int severityRank(ExceptionSeverity s) {
        return switch (s) {
            case URGENT -> 0;
            case WARN -> 1;
            case INFO -> 2;
        };
    }

    private static String scopeJoinErm(ErmScope scope) {
        return scope == ErmScope.MINE ? " AND il.erm_id = ?" : "";
    }

    // ── Detector 1: unsigned offer past expiry ─────────────────────────────

    private List<ExceptionRow> unsignedOfferOverdue(ErmScope scope, UUID callerId) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT o.id AS resource_id, u.id AS intern_id, u.full_name AS intern_name, ")
                .append("       EXTRACT(EPOCH FROM (NOW() - o.expires_at))/86400 AS days_overdue ")
                .append("  FROM offers o ")
                .append("  JOIN applications a ON a.id = o.application_id ")
                .append("  JOIN candidates c   ON c.id = a.candidate_id ")
                .append("  JOIN users u        ON u.id = c.user_id ")
                .append("  LEFT JOIN intern_lifecycles il ON il.user_id = u.id ")
                .append(" WHERE o.status = 'SENT' AND o.expires_at < NOW() ");
        List<Object> params = new ArrayList<>();
        if (scope == ErmScope.MINE) {
            sql.append(" AND (o.created_by = ? OR il.erm_id = ?) ");
            params.add(callerId);
            params.add(callerId);
        }
        sql.append(" ORDER BY o.expires_at ASC");
        return jdbc.query(sql.toString(), params.toArray(),
                (rs, n) -> new ExceptionRow(
                        ExceptionType.UNSIGNED_OFFER_OVERDUE,
                        ExceptionSeverity.URGENT,
                        nullableUuid(rs.getString("intern_id")),
                        rs.getString("intern_name"),
                        Math.max(0, (int) rs.getDouble("days_overdue")),
                        "/careers/erm/offers",
                        nullableUuid(rs.getString("resource_id"))));
    }

    // ── Detector 2: onboarding doc rejected, awaiting resubmission ─────────

    private List<ExceptionRow> onboardingDocRejected(ErmScope scope, UUID callerId) {
        // ERM Phase 8 — switched onboarding_items/onboarding_packets to the
        // document_tasks/document_packets pair. RESEND_REQUESTED is treated
        // the same as REJECTED for the followup window since both push the
        // intern back into "needs to re-upload" state.
        StringBuilder sql = new StringBuilder()
                .append("SELECT dt.id AS resource_id, u.id AS intern_id, u.full_name AS intern_name, ")
                .append("       EXTRACT(EPOCH FROM (NOW() - dt.reviewed_at))/86400 AS days_overdue ")
                .append("  FROM document_tasks dt ")
                .append("  JOIN document_packets dp ON dp.id = dt.packet_id ")
                .append("  JOIN intern_lifecycles il ON il.id = dp.intern_lifecycle_id ")
                .append("  JOIN users u              ON u.id = il.user_id ")
                .append(" WHERE dt.status IN ('REJECTED','RESEND_REQUESTED') ")
                .append("   AND dt.reviewed_at IS NOT NULL ")
                .append("   AND dt.reviewed_at > NOW() - INTERVAL '")
                .append(ErmThresholds.DOC_REJECTED_FOLLOWUP_DAYS).append(" days' ");
        List<Object> params = new ArrayList<>();
        if (scope == ErmScope.MINE) {
            sql.append(" AND il.erm_id = ? ");
            params.add(callerId);
        }
        sql.append(" ORDER BY dt.reviewed_at ASC");
        return jdbc.query(sql.toString(), params.toArray(),
                (rs, n) -> new ExceptionRow(
                        ExceptionType.ONBOARDING_DOC_REJECTED,
                        ExceptionSeverity.WARN,
                        nullableUuid(rs.getString("intern_id")),
                        rs.getString("intern_name"),
                        Math.max(0, (int) rs.getDouble("days_overdue")),
                        "/careers/erm/document-review",
                        nullableUuid(rs.getString("resource_id"))));
    }

    // ── Detector 3: I-9 §2 / E-Verify timing risk ──────────────────────────

    private List<ExceptionRow> i9EverifyTimingRisk(ErmScope scope, UUID callerId) {
        // Active intern, start date reached, but I-9 not COMPLETED N business
        // days past start, OR I-9 done but no EVERIFY EMPLOYMENT_AUTHORIZED yet.
        // Approximates business-day window as N calendar days (Phase 1 trade-off).
        StringBuilder sql = new StringBuilder()
                .append("SELECT u.id AS intern_id, u.full_name AS intern_name, f.id AS resource_id, ")
                .append("       EXTRACT(EPOCH FROM (NOW() - (f.first_day_of_employment + INTERVAL '")
                .append(ErmThresholds.I9_EVERIFY_OVERDUE_DAYS).append(" days')))/86400 AS days_overdue ")
                .append("  FROM i9_forms f ")
                .append("  JOIN candidates c ON c.id = f.candidate_id ")
                .append("  JOIN users u      ON u.id = c.user_id ")
                .append("  JOIN intern_lifecycles il ON il.user_id = u.id ")
                .append(" WHERE il.active_status = 'ACTIVE' ")
                .append("   AND f.first_day_of_employment IS NOT NULL ")
                .append("   AND f.first_day_of_employment + INTERVAL '")
                .append(ErmThresholds.I9_EVERIFY_OVERDUE_DAYS).append(" days' < CURRENT_DATE ")
                .append("   AND ( f.status <> 'COMPLETED' ")
                .append("         OR NOT EXISTS (SELECT 1 FROM everify_cases ec ")
                .append("                          WHERE ec.i9_form_id = f.id ")
                .append("                            AND ec.status = 'EMPLOYMENT_AUTHORIZED') ) ");
        List<Object> params = new ArrayList<>();
        if (scope == ErmScope.MINE) {
            sql.append(" AND il.erm_id = ? ");
            params.add(callerId);
        }
        sql.append(" ORDER BY f.first_day_of_employment ASC");
        return jdbc.query(sql.toString(), params.toArray(),
                (rs, n) -> new ExceptionRow(
                        ExceptionType.I9_EVERIFY_TIMING_RISK,
                        ExceptionSeverity.URGENT,
                        nullableUuid(rs.getString("intern_id")),
                        rs.getString("intern_name"),
                        Math.max(0, (int) rs.getDouble("days_overdue")),
                        "/careers/erm/compliance",
                        nullableUuid(rs.getString("resource_id"))));
    }

    // ── Detector 4: active intern with no open project ─────────────────────

    private List<ExceptionRow> noProjectAssigned(ErmScope scope, UUID callerId) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT il.id AS resource_id, u.id AS intern_id, u.full_name AS intern_name, ")
                .append("       EXTRACT(EPOCH FROM (NOW() - il.started_at))/86400 AS days_overdue ")
                .append("  FROM intern_lifecycles il ")
                .append("  JOIN users u ON u.id = il.user_id ")
                .append(" WHERE il.active_status = 'ACTIVE' ")
                .append("   AND il.started_at IS NOT NULL ")
                .append("   AND il.started_at < NOW() - INTERVAL '")
                .append(ErmThresholds.NO_PROJECT_OVERDUE_DAYS).append(" days' ")
                .append("   AND NOT EXISTS (SELECT 1 FROM project_assignments pa ")
                .append("                     WHERE pa.intern_id = u.id ")
                .append("                       AND pa.status NOT IN ('COMPLETED','RETURNED','CANCELLED')) ");
        List<Object> params = new ArrayList<>();
        if (scope == ErmScope.MINE) {
            sql.append(scopeJoinErm(scope));
            params.add(callerId);
        }
        sql.append(" ORDER BY il.started_at ASC");
        return jdbc.query(sql.toString(), params.toArray(),
                (rs, n) -> new ExceptionRow(
                        ExceptionType.NO_PROJECT_ASSIGNED,
                        ExceptionSeverity.WARN,
                        nullableUuid(rs.getString("intern_id")),
                        rs.getString("intern_name"),
                        Math.max(0, (int) rs.getDouble("days_overdue")),
                        "/careers/erm/active-interns",
                        nullableUuid(rs.getString("resource_id"))));
    }

    // ── Detector 5: trainer weekly meeting missing ─────────────────────────

    private List<ExceptionRow> trainerMeetingMissing(ErmScope scope, UUID callerId) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT il.id AS resource_id, u.id AS intern_id, u.full_name AS intern_name, ")
                .append("       ").append(ErmThresholds.TRAINER_MEETING_MISSING_DAYS)
                .append(" AS days_overdue ")
                .append("  FROM intern_lifecycles il ")
                .append("  JOIN users u ON u.id = il.user_id ")
                .append(" WHERE il.active_status = 'ACTIVE' ")
                .append("   AND il.trainer_id IS NOT NULL ")
                .append("   AND NOT EXISTS (SELECT 1 FROM weekly_meetings wm ")
                .append("                     WHERE wm.intern_lifecycle_id = il.id ")
                .append("                       AND wm.scheduled_for > NOW() - INTERVAL '")
                .append(ErmThresholds.TRAINER_MEETING_MISSING_DAYS).append(" days' ")
                .append("                       AND wm.status IN ('SCHEDULED','COMPLETED')) ");
        List<Object> params = new ArrayList<>();
        if (scope == ErmScope.MINE) {
            sql.append(scopeJoinErm(scope));
            params.add(callerId);
        }
        sql.append(" ORDER BY u.full_name ASC");
        return jdbc.query(sql.toString(), params.toArray(),
                (rs, n) -> new ExceptionRow(
                        ExceptionType.TRAINER_MEETING_MISSING,
                        ExceptionSeverity.WARN,
                        nullableUuid(rs.getString("intern_id")),
                        rs.getString("intern_name"),
                        rs.getInt("days_overdue"),
                        "/careers/erm/active-interns",
                        nullableUuid(rs.getString("resource_id"))));
    }

    // ── Detector 6: evaluation overdue ────────────────────────────────────

    private List<ExceptionRow> evaluationOverdue(ErmScope scope, UUID callerId) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT il.id AS resource_id, u.id AS intern_id, u.full_name AS intern_name, ")
                .append("       GREATEST( ")
                .append("         EXTRACT(EPOCH FROM (NOW() - COALESCE(maxEval.published_at, il.started_at)))/86400, 0 ")
                .append("       ) AS days_overdue ")
                .append("  FROM intern_lifecycles il ")
                .append("  JOIN users u ON u.id = il.user_id ")
                .append("  LEFT JOIN ( ")
                .append("    SELECT intern_lifecycle_id, MAX(published_at) AS published_at ")
                .append("      FROM intern_evaluations ")
                .append("     WHERE evaluation_type = 'MONTHLY' ")
                .append("       AND status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED') ")
                .append("     GROUP BY intern_lifecycle_id ")
                .append("  ) maxEval ON maxEval.intern_lifecycle_id = il.id ")
                .append(" WHERE il.active_status = 'ACTIVE' ")
                .append("   AND il.started_at IS NOT NULL ")
                .append("   AND COALESCE(maxEval.published_at, il.started_at) < NOW() - INTERVAL '")
                .append(ErmThresholds.EVAL_OVERDUE_DAYS).append(" days' ");
        List<Object> params = new ArrayList<>();
        if (scope == ErmScope.MINE) {
            sql.append(scopeJoinErm(scope));
            params.add(callerId);
        }
        sql.append(" ORDER BY days_overdue DESC");
        return jdbc.query(sql.toString(), params.toArray(),
                (rs, n) -> new ExceptionRow(
                        ExceptionType.EVALUATION_OVERDUE,
                        ExceptionSeverity.WARN,
                        nullableUuid(rs.getString("intern_id")),
                        rs.getString("intern_name"),
                        Math.max(0, (int) rs.getDouble("days_overdue")),
                        "/careers/erm/active-interns?filter=eval-overdue",
                        nullableUuid(rs.getString("resource_id"))));
    }

    // ── Detector 7: timesheet missing for previous week ───────────────────

    private List<ExceptionRow> timesheetMissing(ErmScope scope, UUID callerId) {
        // Previous Mon-Sun week. Postgres: date_trunc('week', NOW()) - INTERVAL '7 days'
        StringBuilder sql = new StringBuilder()
                .append("WITH last_week AS (SELECT (date_trunc('week', NOW()) - INTERVAL '7 days')::date AS start_date) ")
                .append("SELECT il.id AS resource_id, u.id AS intern_id, u.full_name AS intern_name, ")
                .append("       EXTRACT(EPOCH FROM (NOW() - (SELECT start_date FROM last_week)))/86400 AS days_overdue ")
                .append("  FROM intern_lifecycles il ")
                .append("  JOIN users u ON u.id = il.user_id ")
                .append(" WHERE il.active_status = 'ACTIVE' ")
                .append("   AND NOT EXISTS (SELECT 1 FROM timesheets t ")
                .append("                     WHERE t.intern_id = u.id ")
                .append("                       AND t.week_start = (SELECT start_date FROM last_week) ")
                .append("                       AND t.status IN ('SUBMITTED','APPROVED')) ");
        List<Object> params = new ArrayList<>();
        if (scope == ErmScope.MINE) {
            sql.append(scopeJoinErm(scope));
            params.add(callerId);
        }
        sql.append(" ORDER BY u.full_name ASC");
        return jdbc.query(sql.toString(), params.toArray(),
                (rs, n) -> new ExceptionRow(
                        ExceptionType.TIMESHEET_MISSING,
                        ExceptionSeverity.INFO,
                        nullableUuid(rs.getString("intern_id")),
                        rs.getString("intern_name"),
                        Math.max(0, (int) rs.getDouble("days_overdue")),
                        "/careers/erm/timesheets",
                        nullableUuid(rs.getString("resource_id"))));
    }

    // ── Detector 8: exit checklist pending past exit_date + window ────────

    private List<ExceptionRow> exitChecklistPending(ErmScope scope, UUID callerId) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT er.id AS resource_id, u.id AS intern_id, u.full_name AS intern_name, ")
                .append("       (CURRENT_DATE - er.exit_date) AS days_overdue ")
                .append("  FROM exit_records er ")
                .append("  JOIN intern_lifecycles il ON il.id = er.intern_lifecycle_id ")
                .append("  JOIN users u ON u.id = il.user_id ")
                .append(" WHERE er.exit_date < CURRENT_DATE - INTERVAL '")
                .append(ErmThresholds.EXIT_CHECKLIST_PENDING_DAYS).append(" days' ")
                .append("   AND (er.access_revocation_done = FALSE OR er.final_documents_archived = FALSE) ");
        List<Object> params = new ArrayList<>();
        if (scope == ErmScope.MINE) {
            sql.append(" AND il.erm_id = ? ");
            params.add(callerId);
        }
        sql.append(" ORDER BY er.exit_date ASC");
        return jdbc.query(sql.toString(), params.toArray(),
                (rs, n) -> new ExceptionRow(
                        ExceptionType.EXIT_CHECKLIST_PENDING,
                        ExceptionSeverity.WARN,
                        nullableUuid(rs.getString("intern_id")),
                        rs.getString("intern_name"),
                        Math.max(0, rs.getInt("days_overdue")),
                        "/careers/erm/exits",
                        nullableUuid(rs.getString("resource_id"))));
    }

    // ── Detector 9: reporting structure incomplete (ERM Phase 4) ──────────
    // Disabled in Phase 8.6.5 — Trainer/Evaluator are auto-linked at offer
    // sign (best-effort) and Manager is assigned inline as needed; missing
    // T/E/M is no longer treated as an urgent exception that should hold up
    // ERM's queue. The detector method is kept (returning empty) so the
    // ExceptionType + scheduler wiring stay valid for any future re-enable.

    private List<ExceptionRow> reportingStructureIncomplete(ErmScope scope, UUID callerId) {
        return java.util.Collections.emptyList();
    }

    // ── Detector 10: work auth expiring within 30 days (ERM Phase 5) ──────

    private List<ExceptionRow> workAuthExpiring30(ErmScope scope, UUID callerId) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT w.id AS resource_id, u.id AS intern_id, u.full_name AS intern_name, ")
                .append("       (CURRENT_DATE - LEAST( ")
                .append("            COALESCE(w.authorized_until, CURRENT_DATE + INTERVAL '999 days')::date, ")
                .append("            COALESCE(w.ead_expiration,    CURRENT_DATE + INTERVAL '999 days')::date, ")
                .append("            COALESCE(w.i20_expiration,    CURRENT_DATE + INTERVAL '999 days')::date ")
                .append("       )) AS days_overdue ")
                .append("  FROM work_authorization_records w ")
                .append("  JOIN users u ON u.id = w.user_id ")
                .append("  JOIN intern_lifecycles il ON il.user_id = u.id ")
                .append(" WHERE il.active_status IN ('ACTIVE','PROSPECTIVE') ")
                .append("   AND LEAST( ")
                .append("        COALESCE(w.authorized_until, CURRENT_DATE + INTERVAL '999 days')::date, ")
                .append("        COALESCE(w.ead_expiration,    CURRENT_DATE + INTERVAL '999 days')::date, ")
                .append("        COALESCE(w.i20_expiration,    CURRENT_DATE + INTERVAL '999 days')::date ")
                .append("       ) <= CURRENT_DATE + INTERVAL '30 days' ");
        List<Object> params = new ArrayList<>();
        if (scope == ErmScope.MINE) {
            sql.append(" AND il.erm_id = ? ");
            params.add(callerId);
        }
        sql.append(" ORDER BY days_overdue DESC");
        return jdbc.query(sql.toString(), params.toArray(),
                (rs, n) -> new ExceptionRow(
                        ExceptionType.WORK_AUTH_EXPIRING_30,
                        ExceptionSeverity.URGENT,
                        nullableUuid(rs.getString("intern_id")),
                        rs.getString("intern_name"),
                        rs.getInt("days_overdue"),
                        "/careers/erm/compliance",
                        nullableUuid(rs.getString("resource_id"))));
    }

    // ── Detector 11: F-1 CPT/OPT/STEM-OPT intern overdue I-983 evaluation ──

    private List<ExceptionRow> i983EvaluationOverdue(ErmScope scope, UUID callerId) {
        // STEM OPT requires a 6-month + 12-month evaluation. Surfaces any
        // active intern with i983_required=TRUE whose latest published
        // monthly evaluation (proxy for the 6-month checkpoint) is older
        // than 180 days, OR who has none yet 180 days into the engagement.
        StringBuilder sql = new StringBuilder()
                .append("SELECT il.id AS resource_id, u.id AS intern_id, u.full_name AS intern_name, ")
                .append("       GREATEST(EXTRACT(EPOCH FROM (NOW() - COALESCE(maxEval.published_at, il.started_at)))/86400 - 180, 0) ")
                .append("         AS days_overdue ")
                .append("  FROM work_authorization_records w ")
                .append("  JOIN users u ON u.id = w.user_id ")
                .append("  JOIN intern_lifecycles il ON il.user_id = u.id ")
                .append("  LEFT JOIN ( ")
                .append("    SELECT intern_lifecycle_id, MAX(published_at) AS published_at ")
                .append("      FROM intern_evaluations ")
                .append("     WHERE evaluation_type = 'MONTHLY' ")
                .append("       AND status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED') ")
                .append("     GROUP BY intern_lifecycle_id ")
                .append("  ) maxEval ON maxEval.intern_lifecycle_id = il.id ")
                .append(" WHERE il.active_status = 'ACTIVE' ")
                .append("   AND w.i983_required = TRUE ")
                .append("   AND il.started_at IS NOT NULL ")
                .append("   AND COALESCE(maxEval.published_at, il.started_at) < NOW() - INTERVAL '180 days' ");
        List<Object> params = new ArrayList<>();
        if (scope == ErmScope.MINE) {
            sql.append(" AND il.erm_id = ? ");
            params.add(callerId);
        }
        sql.append(" ORDER BY days_overdue DESC");
        return jdbc.query(sql.toString(), params.toArray(),
                (rs, n) -> new ExceptionRow(
                        ExceptionType.I983_EVALUATION_OVERDUE,
                        ExceptionSeverity.WARN,
                        nullableUuid(rs.getString("intern_id")),
                        rs.getString("intern_name"),
                        Math.max(0, (int) rs.getDouble("days_overdue")),
                        "/careers/erm/compliance",
                        nullableUuid(rs.getString("resource_id"))));
    }

    // ── Detector 12: E-Verify case stuck in TENTATIVE_NONCONFIRMATION ──────

    private List<ExceptionRow> everifyNonconfirmation(ErmScope scope, UUID callerId) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT ec.id AS resource_id, u.id AS intern_id, u.full_name AS intern_name, ")
                .append("       (CURRENT_DATE - COALESCE(ec.expected_close_by, ec.opened_at::date)) AS days_overdue ")
                .append("  FROM everify_cases ec ")
                .append("  JOIN i9_forms f       ON f.id = ec.i9_form_id ")
                .append("  JOIN candidates c     ON c.id = f.candidate_id ")
                .append("  JOIN users u          ON u.id = c.user_id ")
                .append("  JOIN intern_lifecycles il ON il.user_id = u.id ")
                .append(" WHERE ec.status = 'TENTATIVE_NONCONFIRMATION' ");
        List<Object> params = new ArrayList<>();
        if (scope == ErmScope.MINE) {
            sql.append(" AND il.erm_id = ? ");
            params.add(callerId);
        }
        sql.append(" ORDER BY days_overdue DESC");
        return jdbc.query(sql.toString(), params.toArray(),
                (rs, n) -> new ExceptionRow(
                        ExceptionType.EVERIFY_NONCONFIRMATION,
                        ExceptionSeverity.URGENT,
                        nullableUuid(rs.getString("intern_id")),
                        rs.getString("intern_name"),
                        Math.max(0, rs.getInt("days_overdue")),
                        "/careers/erm/compliance",
                        nullableUuid(rs.getString("resource_id"))));
    }

    // ── Detector 13: trainer weekly meeting missed (Phase 6) ──────────────

    private List<ExceptionRow> missedTrainerMeeting(ErmScope scope, UUID callerId) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT il.id AS resource_id, u.id AS intern_id, u.full_name AS intern_name, ")
                .append("       7 AS days_overdue ")
                .append("  FROM intern_lifecycles il ")
                .append("  JOIN users u ON u.id = il.user_id ")
                .append(" WHERE il.active_status = 'ACTIVE' ")
                .append("   AND il.trainer_id IS NOT NULL ")
                .append("   AND ( EXISTS (SELECT 1 FROM weekly_meetings wm ")
                .append("                    WHERE wm.intern_lifecycle_id = il.id ")
                .append("                      AND wm.status = 'NO_SHOW' ")
                .append("                      AND wm.scheduled_for > NOW() - INTERVAL '14 days') ")
                .append("         OR NOT EXISTS (SELECT 1 FROM weekly_meetings wm ")
                .append("                          WHERE wm.intern_lifecycle_id = il.id ")
                .append("                            AND wm.status IN ('SCHEDULED','COMPLETED') ")
                .append("                            AND wm.scheduled_for > NOW() - INTERVAL '7 days') ) ");
        List<Object> params = new ArrayList<>();
        if (scope == ErmScope.MINE) { sql.append(scopeJoinErm(scope)); params.add(callerId); }
        sql.append(" ORDER BY u.full_name ASC");
        return jdbc.query(sql.toString(), params.toArray(),
                (rs, n) -> new ExceptionRow(
                        ExceptionType.MISSED_TRAINER_MEETING,
                        ExceptionSeverity.WARN,
                        nullableUuid(rs.getString("intern_id")),
                        rs.getString("intern_name"),
                        rs.getInt("days_overdue"),
                        "/careers/erm/active-interns",
                        nullableUuid(rs.getString("resource_id"))));
    }

    // ── Detector 14: project stalled past 75% of duration (Phase 6) ───────

    private List<ExceptionRow> lowProjectProgress(ErmScope scope, UUID callerId) {
        // project_assignments with status IN_PROGRESS where now > 75% of
        // (assignment_date → due_date), no submitted_at yet.
        StringBuilder sql = new StringBuilder()
                .append("SELECT pa.id AS resource_id, u.id AS intern_id, u.full_name AS intern_name, ")
                .append("       GREATEST((CURRENT_DATE - pa.assignment_date), 0) AS days_overdue ")
                .append("  FROM project_assignments pa ")
                .append("  JOIN users u ON u.id = pa.intern_id ")
                .append("  JOIN intern_lifecycles il ON il.user_id = u.id ")
                .append(" WHERE il.active_status = 'ACTIVE' ")
                .append("   AND pa.status = 'IN_PROGRESS' ")
                .append("   AND pa.submitted_at IS NULL ")
                .append("   AND pa.due_date IS NOT NULL ")
                .append("   AND (CURRENT_DATE - pa.assignment_date) > ")
                .append("       (0.75 * GREATEST(1, (pa.due_date - pa.assignment_date))) ");
        List<Object> params = new ArrayList<>();
        if (scope == ErmScope.MINE) { sql.append(" AND il.erm_id = ? "); params.add(callerId); }
        sql.append(" ORDER BY pa.due_date ASC");
        return jdbc.query(sql.toString(), params.toArray(),
                (rs, n) -> new ExceptionRow(
                        ExceptionType.LOW_PROJECT_PROGRESS,
                        ExceptionSeverity.WARN,
                        nullableUuid(rs.getString("intern_id")),
                        rs.getString("intern_name"),
                        Math.max(0, rs.getInt("days_overdue")),
                        "/careers/erm/active-interns",
                        nullableUuid(rs.getString("resource_id"))));
    }

    // ── Detector 15: ≥2 consecutive timesheet rejections (Phase 6) ────────

    private List<ExceptionRow> repeatedTimesheetRejection(ErmScope scope, UUID callerId) {
        // Look at the last 4 weeks of timesheets per intern; count REJECTED
        // rows. ≥2 fires (cheaper than scanning for "consecutive" precisely;
        // the doc tolerates this approximation for INFO severity → URGENT
        // upgrade is the load-bearing piece).
        StringBuilder sql = new StringBuilder()
                .append("SELECT u.id AS intern_id, u.full_name AS intern_name, il.id AS resource_id, ")
                .append("       COUNT(t.id) AS rej_count ")
                .append("  FROM timesheets t ")
                .append("  JOIN candidates c ON c.id = t.intern_id ")
                .append("  JOIN users u ON u.id = c.user_id ")
                .append("  JOIN intern_lifecycles il ON il.user_id = u.id ")
                .append(" WHERE il.active_status = 'ACTIVE' ")
                .append("   AND t.status = 'REJECTED' ")
                .append("   AND t.week_start > CURRENT_DATE - INTERVAL '28 days' ");
        List<Object> params = new ArrayList<>();
        if (scope == ErmScope.MINE) { sql.append(" AND il.erm_id = ? "); params.add(callerId); }
        sql.append(" GROUP BY u.id, u.full_name, il.id ")
                .append(" HAVING COUNT(t.id) >= 2 ")
                .append(" ORDER BY COUNT(t.id) DESC");
        return jdbc.query(sql.toString(), params.toArray(),
                (rs, n) -> new ExceptionRow(
                        ExceptionType.REPEATED_TIMESHEET_REJECTION,
                        ExceptionSeverity.URGENT,
                        nullableUuid(rs.getString("intern_id")),
                        rs.getString("intern_name"),
                        rs.getInt("rej_count"),
                        "/careers/erm/timesheets",
                        nullableUuid(rs.getString("resource_id"))));
    }

    // ── Detector 16: exit stuck > 30 days with PENDING checklist (Phase 7) ─

    private List<ExceptionRow> exitOverdue(ErmScope scope, UUID callerId) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT er.id AS resource_id, u.id AS intern_id, u.full_name AS intern_name, ")
                .append("       (CURRENT_DATE - er.exit_date) AS days_overdue ")
                .append("  FROM exit_records er ")
                .append("  JOIN intern_lifecycles il ON il.id = er.intern_lifecycle_id ")
                .append("  JOIN users u ON u.id = il.user_id ")
                .append(" WHERE er.exit_date < CURRENT_DATE - INTERVAL '30 days' ")
                .append("   AND er.manager_override_at IS NULL ")
                .append("   AND EXISTS (SELECT 1 FROM exit_checklist_items ci ")
                .append("                  WHERE ci.exit_record_id = er.id ")
                .append("                    AND ci.status = 'PENDING') ");
        List<Object> params = new ArrayList<>();
        if (scope == ErmScope.MINE) {
            sql.append(" AND il.erm_id = ? ");
            params.add(callerId);
        }
        sql.append(" ORDER BY er.exit_date ASC");
        return jdbc.query(sql.toString(), params.toArray(),
                (rs, n) -> new ExceptionRow(
                        ExceptionType.EXIT_OVERDUE,
                        ExceptionSeverity.WARN,
                        nullableUuid(rs.getString("intern_id")),
                        rs.getString("intern_name"),
                        Math.max(0, rs.getInt("days_overdue")),
                        "/careers/erm/exits",
                        nullableUuid(rs.getString("resource_id"))));
    }

    // ── Dashboard read-from-table (Phase 6) ────────────────────────────────

    /**
     * Phase 6 read path — counts + top-urgent come from the persisted
     * {@code exception_records} table (status IN OPEN/ASSIGNED/IN_PROGRESS),
     * not from on-demand SQL. Keeps the dashboard response shape identical
     * to Phase 1 while making detection persistent + auditable.
     */
    public ExceptionDetectionResult readFromTable(ErmScope scope, UUID callerId) {
        Map<ExceptionType, Integer> counts = emptyCounts();
        StringBuilder scopeWhere = new StringBuilder();
        List<Object> params = new ArrayList<>();
        if (scope == ErmScope.MINE && callerId != null) {
            scopeWhere.append(" AND (il.erm_id IS NULL OR il.erm_id = ?) ");
            params.add(callerId);
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT er.exception_type AS et, COUNT(*) AS cnt "
                            + "  FROM exception_records er "
                            + "  JOIN intern_lifecycles il ON il.id = er.intern_lifecycle_id "
                            + " WHERE er.status IN ('OPEN','ASSIGNED','IN_PROGRESS') "
                            + scopeWhere
                            + " GROUP BY er.exception_type",
                    params.toArray());
            for (Map<String, Object> row : rows) {
                String t = String.valueOf(row.get("et"));
                Object cnt = row.get("cnt");
                long n = cnt instanceof Number num ? num.longValue() : 0L;
                try {
                    counts.put(ExceptionType.valueOf(t), (int) n);
                } catch (IllegalArgumentException ignored) {
                    // Old/unknown type names skip silently.
                }
            }
        } catch (Exception e) {
            log.warn("[ExceptionDetection] readFromTable count query failed (non-fatal): {}",
                    e.getMessage());
        }

        List<ExceptionRow> topUrgent = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT er.id AS resource_id, er.exception_type AS et, er.severity, "
                            + "       er.subject_user_id, u.full_name, "
                            + "       EXTRACT(EPOCH FROM (NOW() - er.opened_at))/86400 AS days_open "
                            + "  FROM exception_records er "
                            + "  JOIN intern_lifecycles il ON il.id = er.intern_lifecycle_id "
                            + "  JOIN users u ON u.id = er.subject_user_id "
                            + " WHERE er.status IN ('OPEN','ASSIGNED','IN_PROGRESS') "
                            + scopeWhere
                            + " ORDER BY CASE er.severity WHEN 'URGENT' THEN 0 "
                            + "                            WHEN 'WARN'   THEN 1 ELSE 2 END, "
                            + "          er.opened_at ASC "
                            + " LIMIT " + TOP_URGENT_LIMIT,
                    params.toArray());
            for (Map<String, Object> row : rows) {
                String t = String.valueOf(row.get("et"));
                String sev = String.valueOf(row.get("severity"));
                ExceptionType type;
                ExceptionSeverity severity;
                try { type = ExceptionType.valueOf(t); }
                catch (IllegalArgumentException e) { continue; }
                try { severity = ExceptionSeverity.valueOf(sev); }
                catch (IllegalArgumentException e) { severity = ExceptionSeverity.INFO; }
                topUrgent.add(new ExceptionRow(
                        type, severity,
                        row.get("subject_user_id") != null
                                ? UUID.fromString(String.valueOf(row.get("subject_user_id"))) : null,
                        String.valueOf(row.get("full_name")),
                        Math.max(0, (int) ((Number) row.get("days_open")).doubleValue()),
                        "/careers/erm/escalations/" + row.get("resource_id"),
                        row.get("resource_id") != null
                                ? UUID.fromString(String.valueOf(row.get("resource_id"))) : null));
            }
        } catch (Exception e) {
            log.warn("[ExceptionDetection] readFromTable top-urgent query failed (non-fatal): {}",
                    e.getMessage());
        }

        return new ExceptionDetectionResult(counts, topUrgent);
    }

    // ── Scan-job adapter (Phase 6) ─────────────────────────────────────────

    /**
     * Single scheduled-scan entry point. Runs every detector at ALL scope
     * with a null caller (system-wide), produces a deterministic ordered
     * map of {@code ExceptionType → List<DetectorHit>} that the
     * {@code ExceptionScanJob} feeds into UPSERT + auto-resolve passes.
     *
     * <p>Reuses the existing detector SQL by wrapping each ExceptionRow
     * into a DetectorHit. payload_json captures the sanitised
     * {@code daysOverdue} only — never PII.</p>
     */
    public Map<ExceptionType, List<DetectorHit>> scanAllAsHits() {
        Map<ExceptionType, List<DetectorHit>> out = new LinkedHashMap<>();
        for (ExceptionType t : ExceptionType.values()) {
            try {
                List<ExceptionRow> rows = runDetectorByType(t);
                List<DetectorHit> hits = new ArrayList<>(rows.size());
                for (ExceptionRow r : rows) {
                    if (r.internId() == null) continue;
                    UUID lifecycleId = resolveLifecycleId(r.internId());
                    if (lifecycleId == null) continue; // No active lifecycle → skip
                    String payload = String.format(
                            "{\"daysOverdue\":%d}", Math.max(0, r.daysOverdue()));
                    hits.add(new DetectorHit(
                            r.internId(),
                            lifecycleId,
                            subjectResourceTypeFor(t),
                            r.subjectResourceId(),
                            payload));
                }
                out.put(t, hits);
            } catch (Exception e) {
                log.warn("[ExceptionDetection] scan hit produce failed for {}: {}",
                        t, e.getMessage());
                out.put(t, List.of());
            }
        }
        return out;
    }

    private List<ExceptionRow> runDetectorByType(ExceptionType t) {
        return switch (t) {
            case UNSIGNED_OFFER_OVERDUE         -> unsignedOfferOverdue(ErmScope.ALL, null);
            case ONBOARDING_DOC_REJECTED        -> onboardingDocRejected(ErmScope.ALL, null);
            case I9_EVERIFY_TIMING_RISK         -> i9EverifyTimingRisk(ErmScope.ALL, null);
            case NO_PROJECT_ASSIGNED            -> noProjectAssigned(ErmScope.ALL, null);
            case TRAINER_MEETING_MISSING        -> trainerMeetingMissing(ErmScope.ALL, null);
            case EVALUATION_OVERDUE             -> evaluationOverdue(ErmScope.ALL, null);
            case TIMESHEET_MISSING              -> timesheetMissing(ErmScope.ALL, null);
            case EXIT_CHECKLIST_PENDING         -> exitChecklistPending(ErmScope.ALL, null);
            case REPORTING_STRUCTURE_INCOMPLETE -> reportingStructureIncomplete(ErmScope.ALL, null);
            case WORK_AUTH_EXPIRING_30          -> workAuthExpiring30(ErmScope.ALL, null);
            case I983_EVALUATION_OVERDUE        -> i983EvaluationOverdue(ErmScope.ALL, null);
            case EVERIFY_NONCONFIRMATION        -> everifyNonconfirmation(ErmScope.ALL, null);
            case MISSED_TRAINER_MEETING         -> missedTrainerMeeting(ErmScope.ALL, null);
            case LOW_PROJECT_PROGRESS           -> lowProjectProgress(ErmScope.ALL, null);
            case REPEATED_TIMESHEET_REJECTION   -> repeatedTimesheetRejection(ErmScope.ALL, null);
            case EXIT_OVERDUE                   -> exitOverdue(ErmScope.ALL, null);
            // Trainer Phase 0 — detector method registered in Phase 3.
            // For now return empty so the scan job stays inert for this type.
            case TRAINER_ESCALATION             -> java.util.List.of();
            // Evaluator Phase 0 — detector method ships in Evaluator Phase 2.
            // Inert hook so the scan job iterates cleanly over every enum.
            case EVALUATION_ACK_OVERDUE         -> java.util.List.of();
            // Evaluator Phase 4 — detector deferred. Final-eval gaps
            // surface via Reports / History today; the enum slot is
            // reserved so future scan-job promotion is non-breaking.
            case FINAL_EVALUATION_DUE           -> java.util.List.of();
        };
    }

    /** Cheap lookup — returns the active lifecycle id for an intern user. */
    private UUID resolveLifecycleId(UUID userId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id FROM intern_lifecycles WHERE user_id = ?",
                    (rs, n) -> nullableUuid(rs.getString(1)), userId);
        } catch (Exception e) {
            return null;
        }
    }

    private static String subjectResourceTypeFor(ExceptionType t) {
        return switch (t) {
            case UNSIGNED_OFFER_OVERDUE                 -> "OFFER";
            case ONBOARDING_DOC_REJECTED                -> "DOCUMENT_TASK";
            case I9_EVERIFY_TIMING_RISK,
                 I983_EVALUATION_OVERDUE                -> "EVERIFY";
            case NO_PROJECT_ASSIGNED, LOW_PROJECT_PROGRESS -> "PROJECT";
            case TRAINER_MEETING_MISSING,
                 MISSED_TRAINER_MEETING                 -> "MEETING";
            case EVALUATION_OVERDUE                     -> "EVALUATION";
            case TIMESHEET_MISSING,
                 REPEATED_TIMESHEET_REJECTION           -> "TIMESHEET";
            case EXIT_CHECKLIST_PENDING,
                 EXIT_OVERDUE                          -> "EXIT";
            case REPORTING_STRUCTURE_INCOMPLETE         -> "APPLICATION";
            case TRAINER_ESCALATION                     -> "PROJECT";
            case WORK_AUTH_EXPIRING_30                  -> "EVERIFY";
            case EVERIFY_NONCONFIRMATION                -> "EVERIFY";
            case EVALUATION_ACK_OVERDUE                 -> "EVALUATION";
            case FINAL_EVALUATION_DUE                   -> "EVALUATION";
        };
    }

    private static UUID nullableUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    public ExceptionSeverity severityOf(ExceptionType t) {
        return SEVERITY.getOrDefault(t, ExceptionSeverity.INFO);
    }

    /** Exposed for the dashboard service to seed an ordered, zero-filled map. */
    public Map<ExceptionType, Integer> emptyCounts() {
        Map<ExceptionType, Integer> m = new LinkedHashMap<>();
        for (ExceptionType t : ExceptionType.values()) m.put(t, 0);
        return m;
    }
}
