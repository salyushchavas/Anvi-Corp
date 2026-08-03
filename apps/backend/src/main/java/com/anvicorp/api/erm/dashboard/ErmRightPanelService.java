package com.anvicorp.api.erm.dashboard;

import com.anvicorp.api.common.MonthRange;
import com.anvicorp.api.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 1 — ERM right-side panel. Returns 8 quick-action shortcuts with
 * contextual badge counts so ERM sees "what's waiting on me" without
 * leaving the current page. Polled at 60s by the frontend; cache-free
 * (counts must be live for the badges to feel responsive).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErmRightPanelService {

    private final JdbcTemplate jdbc;

    public ErmRightPanelResponse build(User caller) {
        return build(caller, MonthRange.parse(null));
    }

    /**
     * Month-scoped overload — past months short-circuit to an empty
     * panel so scrolling through history doesn't misrepresent stale
     * current-month counts. Current-month path is byte-identical.
     */
    public ErmRightPanelResponse build(User caller, MonthRange range) {
        MonthRange scope = range != null ? range : MonthRange.parse(null);
        if (!scope.isCurrent()) {
            return buildForMonth(caller, scope);
        }
        UUID callerId = caller.getId();
        Instant now = Instant.now();

        long appsPendingReview = safeCount(
                "SELECT COUNT(*) FROM applications "
                        + "WHERE status = 'APPLIED' "
                        + "  AND (erm_owner_id IS NULL OR erm_owner_id = ?)",
                callerId);

        // ERM Phase 3 — count applications truly ready to schedule: status
        // SHORTLISTED AND no SCHEDULED or COMPLETED interview exists. A
        // CANCELLED interview should not block re-scheduling.
        long shortlistedNeedingInterview = safeCount(
                "SELECT COUNT(*) FROM applications a "
                        + "WHERE a.status = 'SHORTLISTED' "
                        + "  AND NOT EXISTS (SELECT 1 FROM interviews i "
                        + "                    WHERE i.application_id = a.id "
                        + "                      AND i.status IN ('SCHEDULED','COMPLETED')) "
                        + "  AND (a.erm_owner_id IS NULL OR a.erm_owner_id = ?)",
                callerId);

        long selectedWithoutOffer = safeCount(
                "SELECT COUNT(*) FROM applications a "
                        + "WHERE a.status IN ('INTERVIEWED','SELECTED_CONDITIONAL') "
                        + "  AND NOT EXISTS (SELECT 1 FROM offers o "
                        + "                    WHERE o.application_id = a.id "
                        + "                      AND o.status <> 'VOIDED') "
                        + "  AND (a.erm_owner_id IS NULL OR a.erm_owner_id = ?)",
                callerId);

        long newHireWithoutPacket = safeCount(
                "SELECT COUNT(*) FROM users u "
                        + "JOIN intern_lifecycles il ON il.user_id = u.id "
                        + "WHERE u.lifecycle_status IN ('EMPLOYEE_ID_CREATED','ONBOARDING_ASSIGNED') "
                        + "  AND NOT EXISTS (SELECT 1 FROM onboarding_packets pk "
                        + "                    WHERE pk.intern_lifecycle_id = il.id) "
                        + "  AND (il.erm_id IS NULL OR il.erm_id = ?)",
                callerId);

        long docsAwaitingReview = safeCount(
                "SELECT COUNT(*) FROM onboarding_items oi "
                        + "JOIN onboarding_packets pk ON pk.id = oi.packet_id "
                        + "JOIN intern_lifecycles il ON il.id = pk.intern_lifecycle_id "
                        + "WHERE oi.status = 'SUBMITTED' "
                        + "  AND (il.erm_id IS NULL OR il.erm_id = ?)",
                callerId);

        long exitChecklistOpen = safeCount(
                "SELECT COUNT(*) FROM exit_records er "
                        + "JOIN intern_lifecycles il ON il.id = er.intern_lifecycle_id "
                        + "WHERE (er.access_revocation_done = FALSE "
                        + "       OR er.final_documents_archived = FALSE) "
                        + "  AND il.erm_id = ?",
                callerId);

        // ERM Phase 6 — read URGENT count directly from exception_records;
        // detection runs in the scheduled scan job, not on every panel load.
        long urgentExceptionCount = safeCount(
                "SELECT COUNT(*) FROM exception_records er "
                        + " JOIN intern_lifecycles il ON il.id = er.intern_lifecycle_id "
                        + " WHERE er.status IN ('OPEN','ASSIGNED','IN_PROGRESS') "
                        + "   AND er.severity = 'URGENT' "
                        + "   AND (il.erm_id IS NULL OR il.erm_id = ?)",
                callerId);

        long todayInterviews = todayInterviewsCount(callerId, now);
        long unreadNotifications = safeCount(
                "SELECT COUNT(*) FROM user_notifications "
                        + "WHERE recipient_user_id = ? AND read_at IS NULL",
                callerId);

        List<ErmRightPanelResponse.QuickAction> actions = new ArrayList<>();
        actions.add(qa("shortlist",
                "Review applications",
                "/careers/erm/applications?status=APPLIED",
                appsPendingReview));
        actions.add(qa("interview",
                "Schedule interview",
                "/careers/erm/shortlist",
                shortlistedNeedingInterview));
        actions.add(qa("offer",
                "Send offer",
                "/careers/erm/interviews?decision=SELECTED",
                selectedWithoutOffer));
        // Phase 8.6.5 — Trainer/Evaluator are auto-linked at offer sign
        // (best-effort) and Manager is assigned inline; no separate
        // "assign reporting structure" gate. The quick action that used
        // to live here is dropped to avoid steering ERM at a non-blocker.
        actions.add(qa("onboarding-assign",
                "Assign onboarding",
                "/careers/erm/new-hire?tab=ready",
                newHireWithoutPacket));
        actions.add(qa("doc-review",
                "Review documents",
                "/careers/erm/onboarding",
                docsAwaitingReview));
        // ERM Phase 5 — Compliance Tracker quick action. Badge sums
        // I-9 §2 timing-risk + E-Verify TNC/overdue + work-auth ≤30 days,
        // so the badge matches what the Compliance KPI mini-row shows.
        long compliancePending = safeCount(
                "SELECT ( "
                        + "  (SELECT COUNT(*) FROM i9_forms f "
                        + "     JOIN candidates c ON c.id = f.candidate_id "
                        + "     JOIN intern_lifecycles il ON il.user_id = c.user_id "
                        + "    WHERE f.first_day_of_employment IS NOT NULL "
                        + "      AND f.section2_signed_at IS NULL "
                        + "      AND f.first_day_of_employment + INTERVAL '3 days' <= CURRENT_DATE + INTERVAL '2 days' "
                        + "      AND (il.erm_id IS NULL OR il.erm_id = ?)) + "
                        + "  (SELECT COUNT(*) FROM everify_cases ec "
                        + "     JOIN i9_forms f ON f.id = ec.i9_form_id "
                        + "     JOIN candidates c ON c.id = f.candidate_id "
                        + "     JOIN intern_lifecycles il ON il.user_id = c.user_id "
                        + "    WHERE (ec.status = 'TENTATIVE_NONCONFIRMATION' "
                        + "       OR (ec.status IN ('PENDING_SUBMISSION','OPEN') AND ec.due_by < CURRENT_DATE)) "
                        + "      AND (il.erm_id IS NULL OR il.erm_id = ?)) + "
                        + "  (SELECT COUNT(*) FROM work_authorization_records w "
                        + "     JOIN intern_lifecycles il ON il.user_id = w.user_id "
                        + "    WHERE il.active_status IN ('ACTIVE','PROSPECTIVE') "
                        + "      AND LEAST(COALESCE(w.authorized_until, DATE '9999-12-31'), "
                        + "                COALESCE(w.ead_expiration,    DATE '9999-12-31'), "
                        + "                COALESCE(w.i20_expiration,    DATE '9999-12-31')) "
                        + "          <= CURRENT_DATE + INTERVAL '30 days' "
                        + "      AND (il.erm_id IS NULL OR il.erm_id = ?)) "
                        + ") AS compliance_pending",
                callerId, callerId, callerId);
        actions.add(qa("compliance",
                "Compliance Tracker",
                "/careers/erm/compliance",
                compliancePending));
        actions.add(qa("escalate",
                "Open escalations",
                "/careers/erm/escalations",
                urgentExceptionCount));
        // ERM Phase 6 — Active Intern Monitor quick action. Badge counts
        // active interns who have ≥1 URGENT open exception (the cheapest
        // proxy for "any URGENT card state" — matches the §9 escalation
        // card severity rule).
        long activeUrgent = safeCount(
                "SELECT COUNT(DISTINCT er.subject_user_id) "
                        + "  FROM exception_records er "
                        + "  JOIN intern_lifecycles il ON il.id = er.intern_lifecycle_id "
                        + " WHERE er.status IN ('OPEN','ASSIGNED','IN_PROGRESS') "
                        + "   AND er.severity = 'URGENT' "
                        + "   AND il.active_status = 'ACTIVE' "
                        + "   AND (il.erm_id IS NULL OR il.erm_id = ?)",
                callerId);
        actions.add(qa("monitor",
                "Active Interns",
                "/careers/erm/active-interns",
                activeUrgent));
        actions.add(qa("exit",
                "Exit checklist",
                "/careers/erm/exits",
                exitChecklistOpen));
        // ERM Phase 2 — Hold follow-up. Counts applications on HOLD; ERM uses
        // this queue to revisit paused candidates without leaving the inbox.
        long holdCount = safeCount(
                "SELECT COUNT(*) FROM applications "
                        + "WHERE status = 'HOLD' "
                        + "  AND (erm_owner_id IS NULL OR erm_owner_id = ?)",
                callerId);
        actions.add(qa("hold-followup",
                "Hold queue",
                "/careers/erm/applications?stage=HOLD",
                holdCount));
        // ERM Phase 7 — direct shortcut to the Reports center. Badge
        // stays 0; this is a pure navigation aid.
        actions.add(qa("reports",
                "Open reports",
                "/careers/erm/reports",
                0L));

        return new ErmRightPanelResponse(actions, unreadNotifications, todayInterviews);
    }

    /**
     * Past-month right-panel. Redefines every badge as "items whose primary
     * timestamp falls in that month" so an ERM scrolling back through
     * history sees real per-month totals rather than the "waiting on me
     * right now" semantics of the current-month path.
     */
    private ErmRightPanelResponse buildForMonth(User caller, MonthRange scope) {
        UUID callerId = caller.getId();
        String start = scope.startInclusive().toString();
        String end = scope.endExclusive().toString();

        long apps = safeCount(
                "SELECT COUNT(*) FROM applications "
                        + "WHERE applied_at >= ?::timestamptz AND applied_at < ?::timestamptz "
                        + "  AND (erm_owner_id IS NULL OR erm_owner_id = ?)",
                start, end, callerId);
        long shortlisted = safeCount(
                "SELECT COUNT(*) FROM applications a "
                        + "WHERE a.status = 'SHORTLISTED' "
                        + "  AND a.applied_at >= ?::timestamptz AND a.applied_at < ?::timestamptz "
                        + "  AND (a.erm_owner_id IS NULL OR a.erm_owner_id = ?)",
                start, end, callerId);
        long selected = safeCount(
                "SELECT COUNT(*) FROM applications a "
                        + "WHERE a.status IN ('INTERVIEWED','SELECTED_CONDITIONAL') "
                        + "  AND COALESCE(a.status_updated_at, a.applied_at) >= ?::timestamptz "
                        + "  AND COALESCE(a.status_updated_at, a.applied_at) < ?::timestamptz "
                        + "  AND (a.erm_owner_id IS NULL OR a.erm_owner_id = ?)",
                start, end, callerId);
        long newHires = safeCount(
                "SELECT COUNT(*) FROM intern_lifecycles il "
                        + "WHERE il.hired_at >= ?::timestamptz AND il.hired_at < ?::timestamptz "
                        + "  AND (il.erm_id IS NULL OR il.erm_id = ?)",
                start, end, callerId);
        long docsAssigned = safeCount(
                "SELECT COUNT(*) FROM document_packets pk "
                        + "JOIN intern_lifecycles il ON il.id = pk.intern_lifecycle_id "
                        + "WHERE pk.assigned_at >= ?::timestamptz "
                        + "  AND pk.assigned_at < ?::timestamptz "
                        + "  AND (il.erm_id IS NULL OR il.erm_id = ?)",
                start, end, callerId);
        long exits = safeCount(
                "SELECT COUNT(*) FROM exit_records er "
                        + "JOIN intern_lifecycles il ON il.id = er.intern_lifecycle_id "
                        + "WHERE er.exit_date >= ? AND er.exit_date < ? "
                        + "  AND il.erm_id = ?",
                java.sql.Date.valueOf(scope.monthStart()),
                java.sql.Date.valueOf(scope.monthEnd()),
                callerId);
        long exceptions = safeCount(
                "SELECT COUNT(*) FROM exception_records er "
                        + "JOIN intern_lifecycles il ON il.id = er.intern_lifecycle_id "
                        + "WHERE er.opened_at >= ?::timestamptz "
                        + "  AND er.opened_at < ?::timestamptz "
                        + "  AND (il.erm_id IS NULL OR il.erm_id = ?)",
                start, end, callerId);
        long everifyInMonth = safeCount(
                "SELECT COUNT(*) FROM everify_cases ec "
                        + "JOIN i9_forms f ON f.id = ec.i9_form_id "
                        + "JOIN candidates c ON c.id = f.candidate_id "
                        + "JOIN intern_lifecycles il ON il.user_id = c.user_id "
                        + "WHERE ec.opened_at >= ?::timestamptz "
                        + "  AND ec.opened_at < ?::timestamptz "
                        + "  AND (il.erm_id IS NULL OR il.erm_id = ?)",
                start, end, callerId);
        long interviewsInMonth = safeCount(
                "SELECT COUNT(*) FROM interviews i "
                        + "JOIN applications a ON a.id = i.application_id "
                        + "WHERE i.scheduled_at >= ?::timestamptz "
                        + "  AND i.scheduled_at < ?::timestamptz "
                        + "  AND (a.erm_owner_id IS NULL OR a.erm_owner_id = ? "
                        + "       OR i.interviewer_id = ?)",
                start, end, callerId, callerId);
        long unreadInMonth = safeCount(
                "SELECT COUNT(*) FROM user_notifications "
                        + "WHERE recipient_user_id = ? AND read_at IS NULL "
                        + "  AND created_at >= ?::timestamptz "
                        + "  AND created_at < ?::timestamptz",
                callerId, start, end);
        long holdInMonth = safeCount(
                "SELECT COUNT(*) FROM applications "
                        + "WHERE status = 'HOLD' "
                        + "  AND applied_at >= ?::timestamptz AND applied_at < ?::timestamptz "
                        + "  AND (erm_owner_id IS NULL OR erm_owner_id = ?)",
                start, end, callerId);

        List<ErmRightPanelResponse.QuickAction> actions = new ArrayList<>();
        actions.add(qa("shortlist", "Review applications",
                "/careers/erm/applications?status=APPLIED", apps));
        actions.add(qa("interview", "Schedule interview",
                "/careers/erm/shortlist", shortlisted));
        actions.add(qa("offer", "Send offer",
                "/careers/erm/interviews?decision=SELECTED", selected));
        actions.add(qa("onboarding-assign", "Assign onboarding",
                "/careers/erm/new-hire?tab=ready", newHires));
        actions.add(qa("doc-review", "Review documents",
                "/careers/erm/onboarding", docsAssigned));
        actions.add(qa("compliance", "Compliance Tracker",
                "/careers/erm/compliance", everifyInMonth));
        actions.add(qa("escalate", "Open escalations",
                "/careers/erm/escalations", exceptions));
        actions.add(qa("monitor", "Active Interns",
                "/careers/erm/active-interns", 0L));
        actions.add(qa("exit", "Exit checklist",
                "/careers/erm/exits", exits));
        actions.add(qa("hold-followup", "Hold queue",
                "/careers/erm/applications?stage=HOLD", holdInMonth));
        actions.add(qa("reports", "Open reports",
                "/careers/erm/reports", 0L));

        return new ErmRightPanelResponse(actions, unreadInMonth, interviewsInMonth);
    }

    private long todayInterviewsCount(UUID callerId, Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        Instant startOfDay = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant startOfTomorrow = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        try {
            Long v = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM interviews i "
                            + "JOIN applications a ON a.id = i.application_id "
                            + "WHERE i.status = 'SCHEDULED' "
                            + "  AND i.scheduled_at >= ? AND i.scheduled_at < ? "
                            + "  AND (a.erm_owner_id IS NULL OR a.erm_owner_id = ? OR i.interviewer_id = ?)",
                    Long.class,
                    java.sql.Timestamp.from(startOfDay),
                    java.sql.Timestamp.from(startOfTomorrow),
                    callerId, callerId);
            return v != null ? v : 0L;
        } catch (Exception e) {
            log.warn("[ErmRightPanel] today-interviews count failed (non-fatal): {}",
                    e.getMessage());
            return 0L;
        }
    }

    private long safeCount(String sql, Object... params) {
        try {
            Long v = jdbc.queryForObject(sql, Long.class, params);
            return v != null ? v : 0L;
        } catch (Exception e) {
            log.warn("[ErmRightPanel] count failed (non-fatal): {} -- {}",
                    e.getMessage(), sql);
            return 0L;
        }
    }

    private static ErmRightPanelResponse.QuickAction qa(String key, String label,
                                                         String href, long badge) {
        return new ErmRightPanelResponse.QuickAction(key, label, href, badge);
    }
}
