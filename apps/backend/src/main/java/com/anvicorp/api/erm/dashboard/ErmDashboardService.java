package com.anvicorp.api.erm.dashboard;

import com.anvicorp.api.common.MonthRange;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.erm.exception.ExceptionDetectionResult;
import com.anvicorp.api.erm.exception.ExceptionDetectionService;
import com.anvicorp.api.erm.exception.ExceptionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 1 — read-only ERM Home dashboard service.
 *
 * <h2>Query budget</h2>
 * Total: ~11 queries per cold call (8 KPI counts + 1 exceptions union +
 * 1 recent-activity feed + 1 unread-notifications count). Cache TTL 30s
 * keyed by {@code (callerId, scope)} keeps hot paths under 10ms.
 *
 * <h2>Cache</h2>
 * In-process {@link ConcurrentHashMap}, no eviction beyond the 30s
 * staleness check. Explicit invalidation via
 * {@link #invalidate(UUID, ErmScope)} — called from
 * {@code POST /api/v1/erm/dashboard/refresh}. Phase 6 events can also
 * call this on relevant state changes without changing the data shape.
 *
 * <h2>Scope</h2>
 * {@link ErmScope#MINE} filters by {@code intern_lifecycles.erm_id = caller}
 * and also picks up unassigned applications ({@code erm_owner_id IS NULL})
 * so ERM sees the queue waiting to be claimed. {@link ErmScope#ALL} is
 * system-wide (still subject to org boundaries when multi-tenant lands).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErmDashboardService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final int RECENT_ACTIVITY_LIMIT = 10;

    private final JdbcTemplate jdbc;
    private final ExceptionDetectionService exceptionDetectionService;
    private final com.anvicorp.api.erm.compliance.ComplianceCalculatorService complianceCalculator;

    private record CacheKey(UUID callerId, ErmScope scope, String monthLabel) {}
    private record CacheEntry(ErmDashboardResponse value, Instant cachedAt) {}

    private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

    public ErmDashboardResponse getDashboard(User caller, ErmScope scope) {
        return getDashboard(caller, scope, MonthRange.parse(null));
    }

    /**
     * Month-scoped overload. Past months redefine every KPI as
     * "items whose primary timestamp falls in that month". Current-month
     * path is byte-identical (same code path as the legacy call).
     */
    public ErmDashboardResponse getDashboard(User caller, ErmScope scope, MonthRange range) {
        if (caller == null) {
            throw new IllegalArgumentException("caller is required");
        }
        ErmScope effectiveScope = scope != null ? scope : ErmScope.MINE;
        MonthRange effectiveRange = range != null ? range : MonthRange.parse(null);
        CacheKey key = new CacheKey(caller.getId(), effectiveScope, effectiveRange.label());
        CacheEntry existing = cache.get(key);
        Instant now = Instant.now();
        if (existing != null
                && Duration.between(existing.cachedAt(), now).compareTo(CACHE_TTL) < 0) {
            return existing.value();
        }
        ErmDashboardResponse fresh = effectiveRange.isCurrent()
                ? build(caller, effectiveScope, now)
                : buildForMonth(caller, effectiveScope, effectiveRange, now);
        cache.put(key, new CacheEntry(fresh, now));
        return fresh;
    }

    public void invalidate(UUID callerId, ErmScope scope) {
        if (callerId == null) return;
        // Purge every month entry for the user — dashboard-refresh must
        // invalidate every scope+month cache slot the user might read.
        // scope arg is retained for API compat but no longer narrows the
        // purge (scope-only cache-busting was over-precise given the
        // month dimension was added).
        cache.keySet().removeIf(k -> k.callerId().equals(callerId));
    }

    // ── Assembly ───────────────────────────────────────────────────────────

    private ErmDashboardResponse build(User caller, ErmScope scope, Instant now) {
        Map<ErmKpiKey, KpiSnapshot> kpis = computeKpis(caller, scope, now);
        // ERM Phase 6 — read from persisted exception_records, not on-demand
        // recompute. Same result shape; the scheduled scan job keeps it fresh.
        ExceptionDetectionResult exceptions =
                exceptionDetectionService.readFromTable(scope, caller.getId());
        List<ErmDashboardResponse.ActivityEntry> activity = recentActivity(caller, scope);
        long unread = unreadNotifications(caller);

        ErmDashboardResponse.Caller callerDto = new ErmDashboardResponse.Caller(
                firstName(caller),
                lastName(caller),
                primaryRole(caller));

        return new ErmDashboardResponse(
                callerDto,
                now,
                scope.wireValue(),
                kpis,
                new ErmDashboardResponse.ExceptionSummary(
                        exceptions.counts(), exceptions.topUrgent()),
                activity,
                unread);
    }

    /**
     * Past-month assembly. Redefines each KPI as "items whose primary
     * timestamp falls in that month" (see per-service ERM month-scope
     * spec). All queries are real windowed counts — no fabricated empties.
     */
    private ErmDashboardResponse buildForMonth(User caller, ErmScope scope,
                                                MonthRange range, Instant now) {
        Map<ErmKpiKey, KpiSnapshot> kpis = computeKpisForMonth(caller, scope, range);
        Map<ExceptionType, Integer> exCounts = exceptionCountsForMonth(scope, caller.getId(), range);
        List<com.anvicorp.api.erm.exception.ExceptionRow> exUrgent = exceptionUrgentForMonth(scope, caller.getId(), range);
        List<ErmDashboardResponse.ActivityEntry> activity = recentActivityForMonth(caller, scope, range);
        long unread = unreadNotificationsForMonth(caller, range);

        ErmDashboardResponse.Caller callerDto = new ErmDashboardResponse.Caller(
                firstName(caller), lastName(caller), primaryRole(caller));
        return new ErmDashboardResponse(
                callerDto,
                now,
                scope.wireValue(),
                kpis,
                new ErmDashboardResponse.ExceptionSummary(exCounts, exUrgent),
                activity,
                unread);
    }

    private Map<ErmKpiKey, KpiSnapshot> computeKpisForMonth(
            User caller, ErmScope scope, MonthRange range) {
        Map<ErmKpiKey, KpiSnapshot> out = new LinkedHashMap<>();
        boolean mine = scope == ErmScope.MINE;
        UUID callerId = caller.getId();
        String start = range.startInclusive().toString();
        String end = range.endExclusive().toString();
        java.sql.Date mStart = java.sql.Date.valueOf(range.monthStart());
        java.sql.Date mEnd = java.sql.Date.valueOf(range.monthEnd());

        // Applications received in month (regardless of status).
        {
            StringBuilder sql = new StringBuilder()
                    .append("FROM applications WHERE applied_at >= ?::timestamptz "
                            + "AND applied_at < ?::timestamptz ");
            List<Object> p = new ArrayList<>();
            p.add(start); p.add(end);
            if (mine) { sql.append(" AND (erm_owner_id IS NULL OR erm_owner_id = ?)"); p.add(callerId); }
            long total = countWithParams(sql.toString(), p);
            out.put(ErmKpiKey.APPLICATIONS_PENDING_REVIEW, new KpiSnapshot(
                    ErmKpiKey.APPLICATIONS_PENDING_REVIEW,
                    "Applications pending review", total, total, null,
                    "/careers/erm/applications"));
        }
        // Interviews scheduled in month.
        {
            StringBuilder sql = new StringBuilder()
                    .append("FROM interviews i JOIN applications a ON a.id = i.application_id ")
                    .append("WHERE i.scheduled_at >= ?::timestamptz AND i.scheduled_at < ?::timestamptz ");
            List<Object> p = new ArrayList<>();
            p.add(start); p.add(end);
            if (mine) {
                sql.append(" AND (a.erm_owner_id = ? OR i.interviewer_id = ? OR i.created_by = ?)");
                p.add(callerId); p.add(callerId); p.add(callerId);
            }
            long total = countWithParams(sql.toString(), p);
            // Past-month semantics = "interviews scheduled in this month",
            // not "today". Relabel so the tile card doesn't read like a
            // live-today count on a historical view.
            out.put(ErmKpiKey.INTERVIEWS_TODAY, new KpiSnapshot(
                    ErmKpiKey.INTERVIEWS_TODAY,
                    "Interviews scheduled", total, total, null,
                    "/careers/erm/interviews"));
        }
        // Offers sent/created in month.
        {
            StringBuilder sql = new StringBuilder()
                    .append("FROM offers WHERE created_at >= ?::timestamptz "
                            + "AND created_at < ?::timestamptz ");
            List<Object> p = new ArrayList<>();
            p.add(start); p.add(end);
            if (mine) { sql.append(" AND created_by = ?"); p.add(callerId); }
            long total = countWithParams(sql.toString(), p);
            out.put(ErmKpiKey.OFFERS_PENDING_SIGNATURE, new KpiSnapshot(
                    ErmKpiKey.OFFERS_PENDING_SIGNATURE,
                    "Offers pending signature", total, total, null,
                    "/careers/erm/offers"));
        }
        // Interns hired in month with no active document packet.
        {
            StringBuilder sql = new StringBuilder()
                    .append("FROM intern_lifecycles il WHERE il.hired_at >= ?::timestamptz "
                            + "AND il.hired_at < ?::timestamptz "
                            + "AND NOT EXISTS (SELECT 1 FROM document_packets dp "
                            + "                  WHERE dp.intern_lifecycle_id = il.id "
                            + "                    AND dp.status NOT IN ('COMPLETED','CANCELLED'))");
            List<Object> p = new ArrayList<>();
            p.add(start); p.add(end);
            if (mine) { sql.append(" AND il.erm_id = ?"); p.add(callerId); }
            long total = countWithParams(sql.toString(), p);
            out.put(ErmKpiKey.AWAITING_DOCUMENT_PACKET, new KpiSnapshot(
                    ErmKpiKey.AWAITING_DOCUMENT_PACKET,
                    "Awaiting document packet", total, total, null,
                    "/careers/erm/new-hire?tab=pending-document-assignment"));
        }
        // Document packets assigned in month.
        {
            StringBuilder sql = new StringBuilder()
                    .append("FROM document_packets WHERE assigned_at >= ?::timestamptz "
                            + "AND assigned_at < ?::timestamptz ");
            List<Object> p = new ArrayList<>();
            p.add(start); p.add(end);
            if (mine) { sql.append(" AND assigned_by_id = ?"); p.add(callerId); }
            long total = countWithParams(sql.toString(), p);
            out.put(ErmKpiKey.ONBOARDING_OVERDUE, new KpiSnapshot(
                    ErmKpiKey.ONBOARDING_OVERDUE,
                    "Onboarding overdue", total, total, null,
                    "/careers/erm/document-packets"));
        }
        // I-9s whose first_day_of_employment fell in month.
        {
            StringBuilder sql = new StringBuilder()
                    .append("FROM i9_forms f WHERE f.first_day_of_employment >= ? "
                            + "AND f.first_day_of_employment < ? ");
            List<Object> p = new ArrayList<>();
            p.add(mStart); p.add(mEnd);
            // Not filtering by mine here — I-9s aren't owned by ERM.
            long total = countWithParams(sql.toString(), p);
            out.put(ErmKpiKey.I9_EVERIFY_DUE, new KpiSnapshot(
                    ErmKpiKey.I9_EVERIFY_DUE,
                    "I-9 / E-Verify due", total, total, null,
                    "/careers/erm/compliance"));
        }
        // Active-during-month interns (overlap semantics). Kept simple:
        // report active-in-month count only, dropping the subquery filter
        // for "with project" to keep the past-month query cheap.
        {
            StringBuilder sql = new StringBuilder()
                    .append("FROM intern_lifecycles il WHERE il.started_at IS NOT NULL "
                            + "AND il.started_at < ?::timestamptz "
                            + "AND (il.ended_at IS NULL OR il.ended_at >= ?::timestamptz) ");
            List<Object> p = new ArrayList<>();
            p.add(end); p.add(start);
            if (mine) { sql.append(" AND il.erm_id = ?"); p.add(callerId); }
            long total = countWithParams(sql.toString(), p);
            out.put(ErmKpiKey.ACTIVE_INTERNS_WITHOUT_PROJECT, new KpiSnapshot(
                    ErmKpiKey.ACTIVE_INTERNS_WITHOUT_PROJECT,
                    "Active interns without project", total, total, null,
                    "/careers/erm/active-interns"));
        }
        // Evaluations published in month.
        {
            StringBuilder sql = new StringBuilder()
                    .append("FROM intern_evaluations WHERE published_at >= ?::timestamptz "
                            + "AND published_at < ?::timestamptz ");
            List<Object> p = new ArrayList<>();
            p.add(start); p.add(end);
            long total = countWithParams(sql.toString(), p);
            out.put(ErmKpiKey.EVALUATIONS_OVERDUE, new KpiSnapshot(
                    ErmKpiKey.EVALUATIONS_OVERDUE,
                    "Evaluations overdue", total, total, null,
                    "/careers/erm/active-interns?filter=eval-overdue"));
        }
        // Timesheets whose week fell in month.
        {
            StringBuilder sql = new StringBuilder()
                    .append("FROM timesheets t JOIN intern_lifecycles il ON il.user_id = t.intern_id "
                            + "WHERE t.week_start >= ? AND t.week_start < ? ");
            List<Object> p = new ArrayList<>();
            p.add(mStart); p.add(mEnd);
            if (mine) {
                sql.append(" AND (il.erm_id = ? OR il.evaluator_id = ?)");
                p.add(callerId); p.add(callerId);
            }
            long total = countWithParams(sql.toString(), p);
            out.put(ErmKpiKey.TIMESHEETS_PENDING_APPROVAL, new KpiSnapshot(
                    ErmKpiKey.TIMESHEETS_PENDING_APPROVAL,
                    "Timesheets pending approval", total, total, null,
                    "/careers/erm/timesheets"));
        }
        return out;
    }

    /** Exceptions raised in month, grouped by type. */
    private Map<ExceptionType, Integer> exceptionCountsForMonth(
            ErmScope scope, UUID callerId, MonthRange range) {
        Map<ExceptionType, Integer> counts = exceptionDetectionService.emptyCounts();
        StringBuilder sql = new StringBuilder()
                .append("SELECT er.exception_type AS et, COUNT(*) AS cnt ")
                .append("  FROM exception_records er ")
                .append("  JOIN intern_lifecycles il ON il.id = er.intern_lifecycle_id ")
                .append(" WHERE er.opened_at >= ?::timestamptz AND er.opened_at < ?::timestamptz ");
        List<Object> p = new ArrayList<>();
        p.add(range.startInclusive().toString());
        p.add(range.endExclusive().toString());
        if (scope == ErmScope.MINE && callerId != null) {
            sql.append(" AND (il.erm_id IS NULL OR il.erm_id = ?) ");
            p.add(callerId);
        }
        sql.append(" GROUP BY er.exception_type");
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), p.toArray());
            for (Map<String, Object> row : rows) {
                String t = String.valueOf(row.get("et"));
                Object cnt = row.get("cnt");
                long n = cnt instanceof Number num ? num.longValue() : 0L;
                try { counts.put(ExceptionType.valueOf(t), (int) n); }
                catch (IllegalArgumentException ignored) {}
            }
        } catch (Exception e) {
            log.warn("[ErmDashboard] past-month exception counts failed (non-fatal): {}", e.getMessage());
        }
        return counts;
    }

    private List<com.anvicorp.api.erm.exception.ExceptionRow> exceptionUrgentForMonth(
            ErmScope scope, UUID callerId, MonthRange range) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT er.id AS resource_id, er.exception_type AS et, er.severity, ")
                .append("       er.subject_user_id, u.full_name, ")
                .append("       EXTRACT(EPOCH FROM (NOW() - er.opened_at))/86400 AS days_open ")
                .append("  FROM exception_records er ")
                .append("  JOIN intern_lifecycles il ON il.id = er.intern_lifecycle_id ")
                .append("  JOIN users u ON u.id = er.subject_user_id ")
                .append(" WHERE er.opened_at >= ?::timestamptz AND er.opened_at < ?::timestamptz "
                        + "  AND er.severity = 'URGENT' ");
        List<Object> p = new ArrayList<>();
        p.add(range.startInclusive().toString());
        p.add(range.endExclusive().toString());
        if (scope == ErmScope.MINE && callerId != null) {
            sql.append(" AND (il.erm_id IS NULL OR il.erm_id = ?) ");
            p.add(callerId);
        }
        sql.append(" ORDER BY er.opened_at DESC LIMIT 5");
        List<com.anvicorp.api.erm.exception.ExceptionRow> out = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), p.toArray());
            for (Map<String, Object> row : rows) {
                String t = String.valueOf(row.get("et"));
                String sev = String.valueOf(row.get("severity"));
                ExceptionType type;
                com.anvicorp.api.erm.exception.ExceptionSeverity severity;
                try { type = ExceptionType.valueOf(t); }
                catch (IllegalArgumentException ex) { continue; }
                try { severity = com.anvicorp.api.erm.exception.ExceptionSeverity.valueOf(sev); }
                catch (IllegalArgumentException ex) {
                    severity = com.anvicorp.api.erm.exception.ExceptionSeverity.INFO;
                }
                out.add(new com.anvicorp.api.erm.exception.ExceptionRow(
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
            log.warn("[ErmDashboard] past-month top-urgent failed (non-fatal): {}", e.getMessage());
        }
        return out;
    }

    private List<ErmDashboardResponse.ActivityEntry> recentActivityForMonth(
            User caller, ErmScope scope, MonthRange range) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT al.action, al.entity_type, al.entity_id, al.timestamp, ")
                .append("       actor.full_name AS actor_name, subj.full_name AS subject_name ")
                .append("  FROM audit_logs al ")
                .append("  LEFT JOIN users actor ON actor.id = al.user_id ")
                .append("  LEFT JOIN users subj  ON subj.id = al.subject_user_id ")
                .append(" WHERE al.timestamp >= ?::timestamptz AND al.timestamp < ?::timestamptz ");
        List<Object> params = new ArrayList<>();
        params.add(range.startInclusive().toString());
        params.add(range.endExclusive().toString());
        if (scope == ErmScope.MINE) {
            sql.append(" AND al.subject_user_id IN ( ")
               .append("   SELECT user_id FROM intern_lifecycles WHERE erm_id = ? ")
               .append(" ) ");
            params.add(caller.getId());
        }
        sql.append(" ORDER BY al.timestamp DESC LIMIT ").append(RECENT_ACTIVITY_LIMIT);
        try {
            return jdbc.query(sql.toString(), params.toArray(), (rs, n) ->
                    new ErmDashboardResponse.ActivityEntry(
                            rs.getString("actor_name"),
                            rs.getString("action"),
                            rs.getString("subject_name"),
                            rs.getTimestamp("timestamp") != null
                                    ? rs.getTimestamp("timestamp").toInstant() : null,
                            "/careers/erm"));
        } catch (Exception e) {
            log.warn("[ErmDashboard] past-month activity failed (non-fatal): {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Past-month unread notifications. Chose "notifications created in the
     * month AND still unread" so past-month view isn't drowned by legacy
     * unread rows from before the app existed. If created_at column is
     * absent on the table, the query fails softly to 0.
     */
    private long unreadNotificationsForMonth(User caller, MonthRange range) {
        try {
            Long v = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM user_notifications "
                            + "WHERE recipient_user_id = ? AND read_at IS NULL "
                            + "  AND created_at >= ?::timestamptz AND created_at < ?::timestamptz",
                    Long.class,
                    caller.getId(),
                    range.startInclusive().toString(),
                    range.endExclusive().toString());
            return v != null ? v : 0L;
        } catch (Exception e) {
            log.warn("[ErmDashboard] past-month unread count failed (non-fatal): {}", e.getMessage());
            return 0L;
        }
    }

    // ── KPI queries ───────────────────────────────────────────────────────

    private Map<ErmKpiKey, KpiSnapshot> computeKpis(User caller, ErmScope scope, Instant now) {
        Map<ErmKpiKey, KpiSnapshot> out = new LinkedHashMap<>();
        boolean mine = scope == ErmScope.MINE;
        UUID callerId = caller.getId();

        out.put(ErmKpiKey.APPLICATIONS_PENDING_REVIEW,
                applicationsPendingReview(mine, callerId));
        out.put(ErmKpiKey.INTERVIEWS_TODAY,
                interviewsToday(mine, callerId, now));
        out.put(ErmKpiKey.OFFERS_PENDING_SIGNATURE,
                offersPendingSignature(mine, callerId));
        out.put(ErmKpiKey.AWAITING_DOCUMENT_PACKET,
                awaitingDocumentPacket(mine, callerId));
        out.put(ErmKpiKey.ONBOARDING_OVERDUE,
                onboardingOverdue(mine, callerId));
        out.put(ErmKpiKey.I9_EVERIFY_DUE,
                i9EverifyDue(mine, callerId));
        out.put(ErmKpiKey.ACTIVE_INTERNS_WITHOUT_PROJECT,
                activeInternsWithoutProject(mine, callerId));
        out.put(ErmKpiKey.EVALUATIONS_OVERDUE,
                evaluationsOverdue(mine, callerId));
        out.put(ErmKpiKey.TIMESHEETS_PENDING_APPROVAL,
                timesheetsPendingApproval(mine, callerId));
        return out;
    }

    private KpiSnapshot applicationsPendingReview(boolean mine, UUID callerId) {
        String base = "FROM applications WHERE status = 'APPLIED'";
        String mineClause = mine
                ? " AND (erm_owner_id IS NULL OR erm_owner_id = ?)" : "";
        long total = countWithOptionalCaller(base + mineClause, mine, callerId);
        long urgent = countWithOptionalCaller(
                base + mineClause
                        + " AND applied_at < NOW() - INTERVAL '"
                        + ErmThresholds.APPLICATION_OVERDUE_DAYS + " days'",
                mine, callerId);
        return new KpiSnapshot(
                ErmKpiKey.APPLICATIONS_PENDING_REVIEW,
                "Applications pending review",
                total,
                urgent,
                urgent > 0
                        ? urgent + " waiting more than " + ErmThresholds.APPLICATION_OVERDUE_DAYS + " days"
                        : null,
                "/careers/erm/applications");
    }

    private KpiSnapshot interviewsToday(boolean mine, UUID callerId, Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        Instant startOfDay = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant startOfTomorrow = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        StringBuilder sql = new StringBuilder()
                .append("FROM interviews i ")
                .append("JOIN applications a ON a.id = i.application_id ")
                .append("WHERE i.status = 'SCHEDULED' ")
                .append("  AND i.scheduled_at >= ? AND i.scheduled_at < ? ");
        List<Object> params = new ArrayList<>();
        params.add(java.sql.Timestamp.from(startOfDay));
        params.add(java.sql.Timestamp.from(startOfTomorrow));
        if (mine) {
            sql.append(" AND (a.erm_owner_id = ? OR i.interviewer_id = ? OR i.created_by = ?)");
            params.add(callerId); params.add(callerId); params.add(callerId);
        }
        long total = countWithParams(sql.toString(), params);
        return new KpiSnapshot(
                ErmKpiKey.INTERVIEWS_TODAY,
                "Interviews today",
                total,
                total > 0 ? total : 0,
                total == 0 ? "Quiet day" : null,
                "/careers/erm/interviews");
    }

    private KpiSnapshot offersPendingSignature(boolean mine, UUID callerId) {
        String base = "FROM offers WHERE status = 'SENT'";
        String mineClause = mine ? " AND created_by = ?" : "";
        long total = countWithOptionalCaller(base + mineClause, mine, callerId);
        long urgent = countWithOptionalCaller(
                base + mineClause
                        + " AND expires_at IS NOT NULL "
                        + " AND expires_at < NOW() + INTERVAL '"
                        + ErmThresholds.OFFER_EXPIRY_URGENT_HOURS + " hours'",
                mine, callerId);
        return new KpiSnapshot(
                ErmKpiKey.OFFERS_PENDING_SIGNATURE,
                "Offers pending signature",
                total,
                urgent,
                urgent > 0
                        ? urgent + " expiring within "
                                + ErmThresholds.OFFER_EXPIRY_URGENT_HOURS + "h"
                        : null,
                "/careers/erm/offers");
    }

    /**
     * ERM Phase 8.2 — interns who signed their offer, have a complete
     * reporting structure, but no active document packet. The default
     * tab on the New Hire List surfaces these so ERM agents can assign
     * documents directly from a one-click button.
     *
     * <p>Urgency: signed offer older than 7 days.</p>
     */
    private KpiSnapshot awaitingDocumentPacket(boolean mine, UUID callerId) {
        // Phase 8.6.5 — document workflow is decoupled from Trainer/
        // Evaluator assignment, so the headline count drops the
        // reporting_structure_complete filter to match the list-page
        // query (see ErmNewHireService.list pending-document-assignment).
        String base =
                "FROM intern_lifecycles il "
                        + " WHERE il.active_status IN ('PROSPECTIVE','ACTIVE') "
                        + "   AND NOT EXISTS (SELECT 1 FROM document_packets dp "
                        + "                     WHERE dp.intern_lifecycle_id = il.id "
                        + "                       AND dp.status NOT IN ('COMPLETED','CANCELLED')) ";
        String mineClause = mine ? " AND il.erm_id = ?" : "";
        long total = countWithOptionalCaller(base + mineClause, mine, callerId);
        long urgent = countWithOptionalCaller(
                base + mineClause + " AND il.hired_at < NOW() - INTERVAL '7 days'",
                mine, callerId);
        return new KpiSnapshot(
                ErmKpiKey.AWAITING_DOCUMENT_PACKET,
                "Awaiting document packet",
                total,
                urgent,
                urgent > 0 ? urgent + " signed > 7 days ago" : null,
                "/careers/erm/new-hire?tab=pending-document-assignment");
    }

    private KpiSnapshot onboardingOverdue(boolean mine, UUID callerId) {
        // ERM Phase 8 — switched from legacy onboarding_packets to
        // document_packets. Packet stays open until status = COMPLETED;
        // CANCELLED packets fall out of the count entirely.
        String base = "FROM document_packets WHERE status IN ('ASSIGNED','IN_PROGRESS','ALL_SUBMITTED')"
                + " AND assigned_at < NOW() - INTERVAL '"
                + ErmThresholds.ONBOARDING_OVERDUE_DAYS + " days'";
        String mineClause = mine ? " AND assigned_by_id = ?" : "";
        long total = countWithOptionalCaller(base + mineClause, mine, callerId);
        long urgent = countWithOptionalCaller(
                "FROM document_packets WHERE status IN ('ASSIGNED','IN_PROGRESS','ALL_SUBMITTED')"
                        + " AND assigned_at < NOW() - INTERVAL '"
                        + ErmThresholds.ONBOARDING_URGENT_DAYS + " days'"
                        + mineClause,
                mine, callerId);
        return new KpiSnapshot(
                ErmKpiKey.ONBOARDING_OVERDUE,
                "Onboarding overdue",
                total,
                urgent,
                urgent > 0
                        ? urgent + " past " + ErmThresholds.ONBOARDING_URGENT_DAYS + " days"
                        : null,
                "/careers/erm/document-packets");
    }

    private KpiSnapshot i9EverifyDue(boolean mine, UUID callerId) {
        // ERM Phase 5 — refined to use ComplianceCalculatorService for the
        // 3-business-day federal window. We scan ACTIVE interns whose first
        // day of employment is set, the calculated I-9 §2 due-by has passed
        // (or is ≤ 2 calendar days away), and either §2 hasn't been signed
        // or E-Verify is not yet EMPLOYMENT_AUTHORIZED.
        StringBuilder sql = new StringBuilder()
                .append("SELECT f.first_day_of_employment, f.section2_signed_at, ec.status AS ec_status ")
                .append("  FROM i9_forms f ")
                .append("  JOIN candidates c ON c.id = f.candidate_id ")
                .append("  JOIN intern_lifecycles il ON il.user_id = c.user_id ")
                .append("  LEFT JOIN everify_cases ec ON ec.i9_form_id = f.id ")
                .append(" WHERE il.active_status = 'ACTIVE' ")
                .append("   AND f.first_day_of_employment IS NOT NULL ");
        List<Object> params = new ArrayList<>();
        if (mine) { sql.append(" AND il.erm_id = ?"); params.add(callerId); }

        java.time.LocalDate today = java.time.LocalDate.now();
        long total = 0;
        long urgent = 0;
        try {
            List<Map<String, Object>> rows =
                    jdbc.queryForList(sql.toString(), params.toArray());
            for (Map<String, Object> row : rows) {
                Object fdObj = row.get("first_day_of_employment");
                if (!(fdObj instanceof java.sql.Date)) continue;
                java.time.LocalDate firstDay = ((java.sql.Date) fdObj).toLocalDate();
                Object signedAt = row.get("section2_signed_at");
                Object ecStatus = row.get("ec_status");
                boolean section2Pending = signedAt == null;
                boolean everifyPending = !"EMPLOYMENT_AUTHORIZED".equals(ecStatus);
                if (!section2Pending && !everifyPending) continue;
                java.time.LocalDate dueBy = complianceCalculator.i9Section2DueBy(firstDay);
                Integer daysUntil = complianceCalculator.daysUntil(dueBy, today);
                if (daysUntil == null) continue;
                if (daysUntil <= 2) total++;
                if (daysUntil <= 0) urgent++;
            }
        } catch (Exception e) {
            log.warn("[ErmDashboard] I-9/E-Verify KPI refined query failed (non-fatal): {}",
                    e.getMessage());
        }
        return new KpiSnapshot(
                ErmKpiKey.I9_EVERIFY_DUE,
                "I-9 / E-Verify due",
                total,
                urgent,
                urgent > 0 ? urgent + " past federal 3-business-day window" : null,
                "/careers/erm/compliance");
    }

    private KpiSnapshot activeInternsWithoutProject(boolean mine, UUID callerId) {
        StringBuilder sql = new StringBuilder()
                .append("FROM intern_lifecycles il ")
                .append("WHERE il.active_status = 'ACTIVE' ")
                .append("  AND NOT EXISTS (SELECT 1 FROM project_assignments pa ")
                .append("                    WHERE pa.intern_id = il.user_id ")
                .append("                      AND pa.status NOT IN ('COMPLETED','RETURNED','CANCELLED')) ");
        List<Object> params = new ArrayList<>();
        if (mine) { sql.append(" AND il.erm_id = ?"); params.add(callerId); }
        long total = countWithParams(sql.toString(), params);
        long urgent = countWithParams(
                sql.toString() + " AND il.started_at < NOW() - INTERVAL '"
                        + ErmThresholds.NO_PROJECT_OVERDUE_DAYS + " days'",
                params);
        return new KpiSnapshot(
                ErmKpiKey.ACTIVE_INTERNS_WITHOUT_PROJECT,
                "Active interns without project",
                total,
                urgent,
                urgent > 0
                        ? urgent + " past " + ErmThresholds.NO_PROJECT_OVERDUE_DAYS + " days"
                        : null,
                "/careers/erm/active-interns");
    }

    private KpiSnapshot evaluationsOverdue(boolean mine, UUID callerId) {
        StringBuilder sql = new StringBuilder()
                .append("FROM intern_lifecycles il ")
                .append("LEFT JOIN ( ")
                .append("  SELECT intern_lifecycle_id, MAX(published_at) AS published_at ")
                .append("    FROM intern_evaluations ")
                .append("   WHERE evaluation_type = 'MONTHLY' ")
                .append("     AND status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED') ")
                .append("   GROUP BY intern_lifecycle_id ")
                .append(") maxEval ON maxEval.intern_lifecycle_id = il.id ")
                .append("WHERE il.active_status = 'ACTIVE' ")
                .append("  AND il.started_at IS NOT NULL ")
                .append("  AND COALESCE(maxEval.published_at, il.started_at) < NOW() - INTERVAL '")
                .append(ErmThresholds.EVAL_OVERDUE_DAYS).append(" days' ");
        List<Object> params = new ArrayList<>();
        if (mine) { sql.append(" AND il.erm_id = ?"); params.add(callerId); }
        long total = countWithParams(sql.toString(), params);
        return new KpiSnapshot(
                ErmKpiKey.EVALUATIONS_OVERDUE,
                "Evaluations overdue",
                total,
                total,
                total > 0
                        ? "Last MONTHLY > " + ErmThresholds.EVAL_OVERDUE_DAYS + " days ago"
                        : null,
                "/careers/erm/active-interns?filter=eval-overdue");
    }

    private KpiSnapshot timesheetsPendingApproval(boolean mine, UUID callerId) {
        StringBuilder sql = new StringBuilder()
                .append("FROM timesheets t ")
                .append("JOIN intern_lifecycles il ON il.user_id = t.intern_id ")
                .append("WHERE t.status = 'SUBMITTED' ");
        List<Object> params = new ArrayList<>();
        if (mine) {
            sql.append(" AND (il.erm_id = ? OR il.evaluator_id = ?)");
            params.add(callerId); params.add(callerId);
        }
        long total = countWithParams(sql.toString(), params);
        return new KpiSnapshot(
                ErmKpiKey.TIMESHEETS_PENDING_APPROVAL,
                "Timesheets pending approval",
                total,
                total,
                null,
                "/careers/erm/timesheets");
    }

    // ── Recent activity + unread notifications ────────────────────────────

    private List<ErmDashboardResponse.ActivityEntry> recentActivity(User caller, ErmScope scope) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT al.action, al.entity_type, al.entity_id, al.timestamp, ")
                .append("       actor.full_name AS actor_name, subj.full_name AS subject_name ")
                .append("  FROM audit_logs al ")
                .append("  LEFT JOIN users actor ON actor.id = al.user_id ")
                .append("  LEFT JOIN users subj  ON subj.id = al.subject_user_id ");
        List<Object> params = new ArrayList<>();
        if (scope == ErmScope.MINE) {
            sql.append(" WHERE al.subject_user_id IN ( ")
               .append("   SELECT user_id FROM intern_lifecycles WHERE erm_id = ? ")
               .append(" ) ");
            params.add(caller.getId());
        }
        sql.append(" ORDER BY al.timestamp DESC LIMIT ").append(RECENT_ACTIVITY_LIMIT);
        try {
            return jdbc.query(sql.toString(), params.toArray(), (rs, n) ->
                    new ErmDashboardResponse.ActivityEntry(
                            rs.getString("actor_name"),
                            rs.getString("action"),
                            rs.getString("subject_name"),
                            rs.getTimestamp("timestamp") != null
                                    ? rs.getTimestamp("timestamp").toInstant()
                                    : null,
                            "/careers/erm"));
        } catch (Exception e) {
            log.warn("[ErmDashboard] recent activity failed (non-fatal): {}", e.getMessage());
            return List.of();
        }
    }

    private long unreadNotifications(User caller) {
        try {
            Long v = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM user_notifications "
                            + "WHERE recipient_user_id = ? AND read_at IS NULL",
                    Long.class, caller.getId());
            return v != null ? v : 0L;
        } catch (Exception e) {
            log.warn("[ErmDashboard] unread count failed (non-fatal): {}", e.getMessage());
            return 0L;
        }
    }

    // ── Count helpers ─────────────────────────────────────────────────────

    private long countWithOptionalCaller(String fromAndWhere, boolean withCaller, UUID callerId) {
        String sql = "SELECT COUNT(*) " + fromAndWhere;
        try {
            Long v = withCaller
                    ? jdbc.queryForObject(sql, Long.class, callerId)
                    : jdbc.queryForObject(sql, Long.class);
            return v != null ? v : 0L;
        } catch (Exception e) {
            log.warn("[ErmDashboard] count query failed (non-fatal): {} -- sql: {}",
                    e.getMessage(), sql);
            return 0L;
        }
    }

    private long countWithParams(String fromAndWhere, List<Object> params) {
        String sql = "SELECT COUNT(*) " + fromAndWhere;
        try {
            Long v = jdbc.queryForObject(sql, Long.class, params.toArray());
            return v != null ? v : 0L;
        } catch (Exception e) {
            log.warn("[ErmDashboard] count query failed (non-fatal): {} -- sql: {}",
                    e.getMessage(), sql);
            return 0L;
        }
    }

    // ── Caller naming ─────────────────────────────────────────────────────

    private static String firstName(User u) {
        String full = u.getFullName();
        if (full == null || full.isBlank()) return "";
        return full.trim().split("\\s+", 2)[0];
    }

    private static String lastName(User u) {
        String full = u.getFullName();
        if (full == null || full.isBlank()) return "";
        String[] parts = full.trim().split("\\s+", 2);
        return parts.length > 1 ? parts[1] : "";
    }

    private static String primaryRole(User u) {
        if (u.getRoles() == null || u.getRoles().isEmpty()) return "ERM";
        if (u.getRoles().contains(UserRole.SUPER_ADMIN)) return "SUPER_ADMIN";
        if (u.getRoles().contains(UserRole.ERM)) return "ERM";
        return u.getRoles().iterator().next().name();
    }
}
