package com.anvicorp.api.todos;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.todos.TodoPanelResponse.TodoBucket;
import com.anvicorp.api.todos.TodoPanelResponse.TodoItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ERM to-do aggregator.
 *
 * <p>Each bucket reuses the exact WHERE predicate of the corresponding
 * {@code ErmDashboardService} KPI so the panel and the ERM Home dashboard
 * stay in lock-step. Same drop-on-write guarantee as Manager/Evaluator:
 * flipping the underlying status column inside the write transaction
 * removes the row from the next fetch.</p>
 *
 * <ul>
 *   <li>Applications pending review — {@code applications.status='APPLIED'}
 *       ({@code erm_owner_id NULL or caller}).</li>
 *   <li>Interviews today — {@code interviews.status='SCHEDULED'} in today's
 *       UTC window (caller-owned via application/interviewer/created_by).</li>
 *   <li>Offers pending signature — {@code offers.status='SENT'}.</li>
 *   <li>Awaiting document packet — active lifecycle without an open packet.</li>
 *   <li>Onboarding overdue — open {@code document_packets} past the
 *       ERM threshold.</li>
 *   <li>I-9 / E-Verify due — federal 3-business-day window compliance check.</li>
 *   <li>Active interns without project — active lifecycle with no open
 *       {@code project_assignments}.</li>
 *   <li>Evaluations overdue — last MONTHLY published > threshold days ago.</li>
 *   <li>Timesheets pending approval — {@code timesheets.status='SUBMITTED'}
 *       for caller-owned lifecycles.</li>
 * </ul>
 *
 * <p>Scope: MINE for plain ERM (owned lifecycles), org-wide for
 * SUPER_ADMIN — mirroring {@link com.anvicorp.api.todos.EvaluatorTodoService}.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErmTodoService {

    private static final int TOP_N = 3;

    private final JdbcTemplate jdbc;
    private final UserTodoDismissalRepository dismissals;

    @Transactional(readOnly = true)
    public TodoPanelResponse build(User caller) {
        if (caller == null) throw new ForbiddenException("Caller required");
        Set<UserRole> roles = caller.getRoles();
        if (roles == null || (!roles.contains(UserRole.ERM)
                && !roles.contains(UserRole.SUPER_ADMIN))) {
            throw new ForbiddenException("ERM or SUPER_ADMIN required");
        }
        boolean orgWide = roles.contains(UserRole.SUPER_ADMIN);
        UUID callerId = caller.getId();

        Set<String> dismissed = dismissals.findTodoKeysForUser(caller.getId());
        Instant lastSeenAt = caller.getTodoPanelLastSeenAt();

        List<TodoBucket> buckets = new ArrayList<>();
        buckets.add(applicationsPendingReview(callerId, orgWide, dismissed));
        buckets.add(interviewsToday(callerId, orgWide));
        buckets.add(offersPendingSignature(callerId, orgWide, dismissed));
        // IDMS Phase 2 — the living-document to-dos.
        buckets.add(idmsDocsAwaitingVerification(callerId, orgWide, dismissed));
        buckets.add(idmsOffersAwaitingSend(callerId, orgWide, dismissed));
        buckets.add(awaitingDocumentPacket(callerId, orgWide));
        buckets.add(onboardingOverdue(callerId, orgWide));
        buckets.add(i9EverifyDue(callerId, orgWide));
        buckets.add(activeInternsWithoutProject(callerId, orgWide));
        buckets.add(evaluationsOverdue(callerId, orgWide));
        buckets.add(timesheetsPendingApproval(callerId, orgWide, dismissed));

        long total = buckets.stream().mapToLong(TodoBucket::count).sum();
        boolean hasNew = computeHasNew(buckets, lastSeenAt);
        return new TodoPanelResponse("ERM", buckets, total, hasNew, lastSeenAt);
    }

    // ── Buckets ────────────────────────────────────────────────────────────

    private TodoBucket applicationsPendingReview(UUID callerId, boolean orgWide,
                                                 Set<String> dismissed) {
        String actionUrl = "/careers/erm/applications";
        String scope = orgWide ? "" : " AND (erm_owner_id IS NULL OR erm_owner_id = ?) ";
        Object[] countParams = orgWide ? new Object[0] : new Object[]{callerId};
        long count = countOrZero(
                "SELECT COUNT(*) FROM applications "
                        + " WHERE status = 'APPLIED' " + scope,
                countParams);
        List<TodoItem> items = new ArrayList<>();
        if (count > 0) {
            Object[] listParams = orgWide ? new Object[]{TOP_N}
                    : new Object[]{callerId, TOP_N};
            for (Map<String, Object> r : safeQueryForList(
                    "SELECT a.id AS a_id, u.full_name, "
                            + "       COALESCE(jp.title, 'General') AS job_title, "
                            + "       a.applied_at "
                            + "  FROM applications a "
                            + "  JOIN candidates c ON c.id = a.candidate_id "
                            + "  JOIN users u ON u.id = c.user_id "
                            + "  LEFT JOIN job_postings jp ON jp.id = a.job_posting_id "
                            + " WHERE a.status = 'APPLIED' "
                            + (orgWide ? "" : " AND (a.erm_owner_id IS NULL OR a.erm_owner_id = ?) ")
                            + " ORDER BY a.applied_at ASC NULLS LAST "
                            + " LIMIT ?", listParams)) {
                UUID aid = uuid(r.get("a_id"));
                String key = "ERM_APPLICATION_REVIEW:" + aid;
                items.add(new TodoItem(
                        key,
                        strOrDash(r.get("full_name")),
                        strOrDash(r.get("job_title")),
                        actionUrl,
                        instantOf(r.get("applied_at")),
                        dismissed.contains(key)));
            }
        }
        return new TodoBucket(
                "ERM_APPLICATION_REVIEW",
                "Applications pending review",
                "user-check",
                count,
                actionUrl,
                severity(count, 20, 5),
                items);
    }

    private TodoBucket interviewsToday(UUID callerId, boolean orgWide) {
        String actionUrl = "/careers/erm/interviews";
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant startOfDay = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant startOfTomorrow = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        String sql = "SELECT COUNT(*) FROM interviews i "
                + " JOIN applications a ON a.id = i.application_id "
                + " WHERE i.status = 'SCHEDULED' "
                + "   AND i.scheduled_at >= ? AND i.scheduled_at < ? "
                + (orgWide ? "" : " AND (a.erm_owner_id = ? OR i.interviewer_id = ? OR i.created_by = ?) ");
        Object[] params = orgWide
                ? new Object[]{java.sql.Timestamp.from(startOfDay),
                               java.sql.Timestamp.from(startOfTomorrow)}
                : new Object[]{java.sql.Timestamp.from(startOfDay),
                               java.sql.Timestamp.from(startOfTomorrow),
                               callerId, callerId, callerId};
        long count = countOrZero(sql, params);
        return new TodoBucket(
                "ERM_INTERVIEW_TODAY",
                "Interviews today",
                "calendar-plus",
                count,
                actionUrl,
                count > 0 ? "WARN" : "INFO",
                List.of());
    }

    private TodoBucket offersPendingSignature(UUID callerId, boolean orgWide,
                                              Set<String> dismissed) {
        String actionUrl = "/careers/erm/offers";
        String scope = orgWide ? "" : " AND created_by = ? ";
        Object[] countParams = orgWide ? new Object[0] : new Object[]{callerId};
        long count = countOrZero(
                "SELECT COUNT(*) FROM offers WHERE status = 'SENT' " + scope,
                countParams);
        List<TodoItem> items = new ArrayList<>();
        if (count > 0) {
            Object[] listParams = orgWide ? new Object[]{TOP_N}
                    : new Object[]{callerId, TOP_N};
            for (Map<String, Object> r : safeQueryForList(
                    "SELECT o.id AS o_id, o.expires_at, u.full_name "
                            + "  FROM offers o "
                            + "  JOIN applications a ON a.id = o.application_id "
                            + "  JOIN candidates c ON c.id = a.candidate_id "
                            + "  JOIN users u ON u.id = c.user_id "
                            + " WHERE o.status = 'SENT' "
                            + (orgWide ? "" : " AND o.created_by = ? ")
                            + " ORDER BY o.expires_at ASC NULLS LAST "
                            + " LIMIT ?", listParams)) {
                UUID oid = uuid(r.get("o_id"));
                String key = "ERM_OFFER_PENDING_SIGN:" + oid;
                Object exp = r.get("expires_at");
                String sub = exp != null ? ("Expires " + exp) : "No expiry set";
                items.add(new TodoItem(
                        key,
                        strOrDash(r.get("full_name")),
                        sub,
                        actionUrl,
                        instantOf(r.get("expires_at")),
                        dismissed.contains(key)));
            }
        }
        return new TodoBucket(
                "ERM_OFFER_PENDING_SIGN",
                "Offers pending signature",
                "file-text",
                count,
                actionUrl,
                severity(count, 5, 1),
                items);
    }

    /**
     * IDMS Phase 2 — instances the ERM has to verify (intern signed and
     * submitted; ERM's turn). Rows drain the moment ERM verifies or
     * returns, per the drop-on-write guarantee.
     */
    private TodoBucket idmsDocsAwaitingVerification(UUID callerId, boolean orgWide,
                                                    Set<String> dismissed) {
        String actionUrl = "/careers/erm/offers?tab=verifying";
        String scope = orgWide ? "" : " AND created_by_erm_id = ? ";
        Object[] countParams = orgWide ? new Object[0] : new Object[]{callerId};
        long count = countOrZero(
                "SELECT COUNT(*) FROM document_instances "
                        + " WHERE status = 'INTERN_SUBMITTED' " + scope,
                countParams);
        List<TodoItem> items = new ArrayList<>();
        if (count > 0) {
            Object[] listParams = orgWide ? new Object[]{TOP_N}
                    : new Object[]{callerId, TOP_N};
            for (Map<String, Object> r : safeQueryForList(
                    "SELECT di.id AS d_id, di.template_title, di.intern_submitted_at, "
                            + "       u.full_name "
                            + "  FROM document_instances di "
                            + "  JOIN users u ON u.id = di.intern_user_id "
                            + " WHERE di.status = 'INTERN_SUBMITTED' "
                            + (orgWide ? "" : " AND di.created_by_erm_id = ? ")
                            + " ORDER BY di.intern_submitted_at ASC NULLS LAST "
                            + " LIMIT ?", listParams)) {
                UUID did = uuid(r.get("d_id"));
                String key = "ERM_IDMS_VERIFY:" + did;
                items.add(new TodoItem(
                        key,
                        strOrDash(r.get("full_name")),
                        strOrDash(r.get("template_title")),
                        actionUrl,
                        instantOf(r.get("intern_submitted_at")),
                        dismissed.contains(key)));
            }
        }
        return new TodoBucket(
                "ERM_IDMS_VERIFY",
                "Documents awaiting verification",
                "file-check",
                count,
                actionUrl,
                severity(count, 5, 1),
                items);
    }

    /**
     * IDMS Phase 2 — interns who cleared interviews but have no offer
     * document sent yet. Same predicate as the legacy Awaiting-Offer
     * queue so the KPI stays in lock-step with the cockpit page.
     */
    private TodoBucket idmsOffersAwaitingSend(UUID callerId, boolean orgWide,
                                              Set<String> dismissed) {
        String actionUrl = "/careers/erm/offers?tab=awaiting";
        String scope = orgWide ? "" : " AND (a.erm_owner_id IS NULL OR a.erm_owner_id = ?) ";
        Object[] countParams = orgWide ? new Object[0] : new Object[]{callerId};
        long count = countOrZero(
                "SELECT COUNT(*) FROM applications a "
                        + " WHERE a.status = 'INTERVIEWED' "
                        + "   AND EXISTS ( "
                        + "        SELECT 1 FROM interviews iv "
                        + "         WHERE iv.application_id = a.id "
                        + "           AND UPPER(COALESCE(iv.decision,'')) = 'SELECTED' ) "
                        + "   AND NOT EXISTS ( "
                        + "        SELECT 1 FROM offers o "
                        + "         WHERE o.application_id = a.id "
                        + "           AND o.status IN ('SENT','SIGNED') ) "
                        + "   AND NOT EXISTS ( "
                        + "        SELECT 1 FROM document_instances di "
                        + "         WHERE di.intern_user_id = ( "
                        + "               SELECT c.user_id FROM candidates c "
                        + "                WHERE c.id = a.candidate_id LIMIT 1) "
                        + "           AND di.status NOT IN ('REVOKED','SUPERSEDED','VOIDED') ) "
                        + scope,
                countParams);
        List<TodoItem> items = new ArrayList<>();
        if (count > 0) {
            Object[] listParams = orgWide ? new Object[]{TOP_N}
                    : new Object[]{callerId, TOP_N};
            for (Map<String, Object> r : safeQueryForList(
                    "SELECT a.id AS a_id, u.full_name, "
                            + "       COALESCE(jp.title,'General') AS job_title "
                            + "  FROM applications a "
                            + "  JOIN candidates c ON c.id = a.candidate_id "
                            + "  JOIN users u ON u.id = c.user_id "
                            + "  LEFT JOIN job_postings jp ON jp.id = a.job_posting_id "
                            + " WHERE a.status = 'INTERVIEWED' "
                            + "   AND EXISTS ( "
                            + "        SELECT 1 FROM interviews iv "
                            + "         WHERE iv.application_id = a.id "
                            + "           AND UPPER(COALESCE(iv.decision,'')) = 'SELECTED' ) "
                            + "   AND NOT EXISTS ( "
                            + "        SELECT 1 FROM offers o "
                            + "         WHERE o.application_id = a.id "
                            + "           AND o.status IN ('SENT','SIGNED') ) "
                            + "   AND NOT EXISTS ( "
                            + "        SELECT 1 FROM document_instances di "
                            + "         WHERE di.intern_user_id = c.user_id "
                            + "           AND di.status NOT IN ('REVOKED','SUPERSEDED','VOIDED') ) "
                            + (orgWide ? "" : " AND (a.erm_owner_id IS NULL OR a.erm_owner_id = ?) ")
                            + " ORDER BY a.applied_at ASC NULLS LAST "
                            + " LIMIT ?", listParams)) {
                UUID aid = uuid(r.get("a_id"));
                String key = "ERM_IDMS_AWAITING_SEND:" + aid;
                items.add(new TodoItem(
                        key,
                        strOrDash(r.get("full_name")),
                        strOrDash(r.get("job_title")),
                        actionUrl,
                        null,
                        dismissed.contains(key)));
            }
        }
        return new TodoBucket(
                "ERM_IDMS_AWAITING_SEND",
                "Offers awaiting send",
                "file-plus",
                count,
                actionUrl,
                severity(count, 10, 3),
                items);
    }

    private TodoBucket awaitingDocumentPacket(UUID callerId, boolean orgWide) {
        String actionUrl = "/careers/erm/new-hire?tab=pending-document-assignment";
        String scope = orgWide ? "" : " AND il.erm_id = ? ";
        Object[] params = orgWide ? new Object[0] : new Object[]{callerId};
        long count = countOrZero(
                "SELECT COUNT(*) FROM intern_lifecycles il "
                        + " WHERE il.active_status IN ('PROSPECTIVE','ACTIVE') "
                        + "   AND NOT EXISTS (SELECT 1 FROM document_packets dp "
                        + "                     WHERE dp.intern_lifecycle_id = il.id "
                        + "                       AND dp.status NOT IN ('COMPLETED','CANCELLED')) "
                        + scope,
                params);
        return new TodoBucket(
                "ERM_AWAITING_DOC_PACKET",
                "Awaiting document packet",
                "file-text",
                count,
                actionUrl,
                severity(count, 10, 1),
                List.of());
    }

    private TodoBucket onboardingOverdue(UUID callerId, boolean orgWide) {
        String actionUrl = "/careers/erm/document-packets";
        String scope = orgWide ? "" : " AND assigned_by_id = ? ";
        Object[] params = orgWide ? new Object[0] : new Object[]{callerId};
        long count = countOrZero(
                "SELECT COUNT(*) FROM document_packets "
                        + " WHERE status IN ('ASSIGNED','IN_PROGRESS','ALL_SUBMITTED') "
                        + "   AND assigned_at < NOW() - INTERVAL '"
                        + com.anvicorp.api.erm.dashboard.ErmThresholds.ONBOARDING_OVERDUE_DAYS
                        + " days' "
                        + scope,
                params);
        return new TodoBucket(
                "ERM_ONBOARDING_OVERDUE",
                "Onboarding overdue",
                "alert-triangle",
                count,
                actionUrl,
                count > 0 ? "URGENT" : "INFO",
                List.of());
    }

    private TodoBucket i9EverifyDue(UUID callerId, boolean orgWide) {
        String actionUrl = "/careers/erm/compliance";
        // Cheap proxy for the KPI in ErmDashboardService — a caller-scoped
        // count of §2 pending OR E-Verify non-authorized rows. The full
        // 3-business-day window calc lives in ErmDashboardService; here we
        // just surface the count for a to-do gate.
        String scope = orgWide ? "" : " AND il.erm_id = ? ";
        Object[] params = orgWide ? new Object[0] : new Object[]{callerId};
        long count = countOrZero(
                "SELECT COUNT(*) FROM i9_forms f "
                        + "  JOIN candidates c ON c.id = f.candidate_id "
                        + "  JOIN intern_lifecycles il ON il.user_id = c.user_id "
                        + "  LEFT JOIN everify_cases ec ON ec.i9_form_id = f.id "
                        + " WHERE il.active_status = 'ACTIVE' "
                        + "   AND f.first_day_of_employment IS NOT NULL "
                        + "   AND ( f.section2_signed_at IS NULL "
                        + "         OR ec.status IS NULL "
                        + "         OR ec.status <> 'EMPLOYMENT_AUTHORIZED' ) "
                        + scope,
                params);
        return new TodoBucket(
                "ERM_I9_EVERIFY_DUE",
                "I-9 / E-Verify due",
                "check-square",
                count,
                actionUrl,
                count > 0 ? "URGENT" : "INFO",
                List.of());
    }

    private TodoBucket activeInternsWithoutProject(UUID callerId, boolean orgWide) {
        String actionUrl = "/careers/erm/active-interns";
        String scope = orgWide ? "" : " AND il.erm_id = ? ";
        Object[] params = orgWide ? new Object[0] : new Object[]{callerId};
        long count = countOrZero(
                "SELECT COUNT(*) FROM intern_lifecycles il "
                        + " WHERE il.active_status = 'ACTIVE' "
                        + "   AND NOT EXISTS (SELECT 1 FROM project_assignments pa "
                        + "                     WHERE pa.intern_id = il.user_id "
                        + "                       AND pa.status NOT IN ('COMPLETED','RETURNED','CANCELLED')) "
                        + scope,
                params);
        return new TodoBucket(
                "ERM_ACTIVE_NO_PROJECT",
                "Active interns without project",
                "user-check",
                count,
                actionUrl,
                severity(count, 5, 1),
                List.of());
    }

    private TodoBucket evaluationsOverdue(UUID callerId, boolean orgWide) {
        String actionUrl = "/careers/erm/active-interns?filter=eval-overdue";
        String scope = orgWide ? "" : " AND il.erm_id = ? ";
        Object[] params = orgWide ? new Object[0] : new Object[]{callerId};
        long count = countOrZero(
                "SELECT COUNT(*) FROM intern_lifecycles il "
                        + "  LEFT JOIN ( "
                        + "    SELECT intern_lifecycle_id, MAX(published_at) AS published_at "
                        + "      FROM intern_evaluations "
                        + "     WHERE evaluation_type = 'MONTHLY' "
                        + "       AND status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED') "
                        + "     GROUP BY intern_lifecycle_id "
                        + "  ) maxEval ON maxEval.intern_lifecycle_id = il.id "
                        + " WHERE il.active_status = 'ACTIVE' "
                        + "   AND il.started_at IS NOT NULL "
                        + "   AND COALESCE(maxEval.published_at, il.started_at) < NOW() - INTERVAL '"
                        + com.anvicorp.api.erm.dashboard.ErmThresholds.EVAL_OVERDUE_DAYS
                        + " days' "
                        + scope,
                params);
        return new TodoBucket(
                "ERM_EVALUATIONS_OVERDUE",
                "Evaluations overdue",
                "clock",
                count,
                actionUrl,
                count > 0 ? "URGENT" : "INFO",
                List.of());
    }

    private TodoBucket timesheetsPendingApproval(UUID callerId, boolean orgWide,
                                                 Set<String> dismissed) {
        String actionUrl = "/careers/erm/timesheets";
        String scope = orgWide ? "" : " AND (il.erm_id = ? OR il.evaluator_id = ?) ";
        Object[] countParams = orgWide ? new Object[0] : new Object[]{callerId, callerId};
        long count = countOrZero(
                "SELECT COUNT(*) FROM timesheets t "
                        + " JOIN intern_lifecycles il ON il.user_id = t.intern_id "
                        + " WHERE t.status = 'SUBMITTED' "
                        + scope,
                countParams);
        List<TodoItem> items = new ArrayList<>();
        if (count > 0) {
            Object[] listParams = orgWide ? new Object[]{TOP_N}
                    : new Object[]{callerId, callerId, TOP_N};
            for (Map<String, Object> r : safeQueryForList(
                    "SELECT t.id AS t_id, t.week_start, t.updated_at, u.full_name "
                            + "  FROM timesheets t "
                            + "  JOIN intern_lifecycles il ON il.user_id = t.intern_id "
                            + "  JOIN users u ON u.id = il.user_id "
                            + " WHERE t.status = 'SUBMITTED' "
                            + (orgWide ? "" : " AND (il.erm_id = ? OR il.evaluator_id = ?) ")
                            + " ORDER BY t.updated_at ASC NULLS LAST "
                            + " LIMIT ?", listParams)) {
                UUID tid = uuid(r.get("t_id"));
                String key = "ERM_TIMESHEET_APPROVE:" + tid;
                items.add(new TodoItem(
                        key,
                        strOrDash(r.get("full_name")),
                        "Week of " + strOrDash(r.get("week_start")),
                        actionUrl,
                        instantOf(r.get("updated_at")),
                        dismissed.contains(key)));
            }
        }
        return new TodoBucket(
                "ERM_TIMESHEET_APPROVE",
                "Timesheets pending approval",
                "clock",
                count,
                actionUrl,
                severity(count, 10, 3),
                items);
    }

    // ── Helpers (parallel to Manager/EvaluatorTodoService) ────────────────

    private boolean computeHasNew(List<TodoBucket> buckets, Instant lastSeenAt) {
        boolean anyPending = buckets.stream().anyMatch(b -> b.count() > 0);
        if (!anyPending) return false;
        if (lastSeenAt == null) return true;
        for (TodoBucket b : buckets) {
            for (TodoItem it : b.topItems()) {
                if (it.firstAppearedAt() != null
                        && it.firstAppearedAt().isAfter(lastSeenAt)) {
                    return true;
                }
            }
        }
        return false;
    }

    private long countOrZero(String sql, Object... params) {
        try {
            Long v = jdbc.queryForObject(sql, Long.class, params);
            return v == null ? 0L : v;
        } catch (Exception e) {
            log.debug("[ErmTodo] count fallback ({}): {}", sql, e.getMessage());
            return 0L;
        }
    }

    private List<Map<String, Object>> safeQueryForList(String sql, Object... params) {
        try {
            return jdbc.queryForList(sql, params);
        } catch (Exception e) {
            log.debug("[ErmTodo] queryForList fallback ({}): {}", sql, e.getMessage());
            return List.of();
        }
    }

    private static String severity(long count, long urgentThreshold, long warnThreshold) {
        if (count >= urgentThreshold) return "URGENT";
        if (count >= warnThreshold) return "WARN";
        return "INFO";
    }

    private static UUID uuid(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        try { return UUID.fromString(o.toString()); } catch (Exception e) { return null; }
    }

    private static String strOrDash(Object o) {
        return o == null ? "—" : o.toString();
    }

    private static Instant instantOf(Object o) {
        if (o == null) return null;
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (o instanceof Instant i) return i;
        if (o instanceof java.sql.Date d) return d.toInstant();
        return null;
    }
}
