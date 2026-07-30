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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * MANAGER to-do aggregator.
 *
 * <p>Every bucket reuses the exact WHERE clause of the corresponding
 * queue service so the to-do panel and the queue page stay in lock-step:</p>
 * <ul>
 *   <li>Hire approvals — {@code ManagerHireApprovalService.list()}
 *       ({@code iv.status='COMPLETED' AND iv.manager_hire_decision IN ('PENDING','HOLD')}).</li>
 *   <li>Timesheets to approve — {@code ManagerTimesheetService.list} VERIFIED
 *       ({@code ts.status='VERIFIED'}).</li>
 *   <li>Weekly reports to approve — {@code WeeklyReportService.listVerifiedForManager}
 *       ({@code wr.status='VERIFIED'}).</li>
 *   <li>Recording approvals — {@code ManagerRecordingApprovalService.listPending}
 *       ({@code intern_evaluations.recording_approval_status='PENDING_APPROVAL'}).</li>
 * </ul>
 *
 * <p>Auto-resolve guarantee: each of these WHERE predicates is flipped
 * inside the same {@code @Transactional} write endpoint (see
 * {@code ManagerTimesheetApprovalService.approve} → {@code TimesheetService.approve}
 * setting {@code TimesheetStatus.APPROVED}). Once the underlying row's
 * status changes, this bucket's next fetch stops emitting the row and
 * the panel visibly drops it on the next poll.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerTodoService {

    private static final int TOP_N = 3;

    private final JdbcTemplate jdbc;
    private final UserTodoDismissalRepository dismissals;

    @Transactional(readOnly = true)
    public TodoPanelResponse build(User caller) {
        if (caller == null) throw new ForbiddenException("Caller required");
        Set<UserRole> roles = caller.getRoles();
        if (roles == null || (!roles.contains(UserRole.MANAGER)
                && !roles.contains(UserRole.SUPER_ADMIN))) {
            throw new ForbiddenException("MANAGER or SUPER_ADMIN required");
        }

        Set<String> dismissed = dismissals.findTodoKeysForUser(caller.getId());
        Instant lastSeenAt = caller.getTodoPanelLastSeenAt();

        List<TodoBucket> buckets = new ArrayList<>();
        buckets.add(hireApprovals(dismissed));
        buckets.add(timesheetsToApprove(dismissed));
        buckets.add(weeklyReportsToApprove(dismissed));
        buckets.add(recordingApprovals(dismissed));

        long total = buckets.stream().mapToLong(TodoBucket::count).sum();
        boolean hasNew = computeHasNew(buckets, lastSeenAt);
        return new TodoPanelResponse(
                "MANAGER", buckets, total, hasNew, lastSeenAt);
    }

    // ── Buckets ────────────────────────────────────────────────────────

    private TodoBucket hireApprovals(Set<String> dismissed) {
        String actionUrl = "/careers/manager/hire-approvals";
        long count = countOrZero(
                "SELECT COUNT(*) FROM interviews iv "
                        + " WHERE iv.status = 'COMPLETED' "
                        + "   AND iv.manager_hire_decision IN ('PENDING','HOLD')");
        List<TodoItem> items = new ArrayList<>();
        if (count > 0) {
            for (Map<String, Object> r : safeQueryForList(
                    "SELECT iv.id AS iv_id, u.full_name, "
                            + "       COALESCE(jp.title, 'General') AS job_title, "
                            + "       iv.feedback_submitted_at "
                            + "  FROM interviews iv "
                            + "  JOIN applications a ON a.id = iv.application_id "
                            + "  JOIN candidates c ON c.id = a.candidate_id "
                            + "  JOIN users u ON u.id = c.user_id "
                            + "  LEFT JOIN job_postings jp ON jp.id = a.job_posting_id "
                            + " WHERE iv.status = 'COMPLETED' "
                            + "   AND iv.manager_hire_decision IN ('PENDING','HOLD') "
                            + " ORDER BY iv.feedback_submitted_at ASC NULLS LAST "
                            + " LIMIT ?", TOP_N)) {
                UUID ivId = uuid(r.get("iv_id"));
                String key = "MANAGER_HIRE_APPROVAL:" + ivId;
                items.add(new TodoItem(
                        key,
                        strOrDash(r.get("full_name")),
                        (String) r.get("job_title"),
                        actionUrl,
                        instantOf(r.get("feedback_submitted_at")),
                        dismissed.contains(key)));
            }
        }
        return new TodoBucket(
                "MANAGER_HIRE_APPROVAL",
                "Hire approvals",
                "user-check",
                count,
                actionUrl,
                severity(count, 5, 1),
                items);
    }

    private TodoBucket timesheetsToApprove(Set<String> dismissed) {
        String actionUrl = "/careers/manager/timesheet-approvals?status=VERIFIED";
        long count = countOrZero(
                "SELECT COUNT(*) FROM timesheets ts WHERE ts.status = 'VERIFIED'");
        List<TodoItem> items = new ArrayList<>();
        if (count > 0) {
            for (Map<String, Object> r : safeQueryForList(
                    "SELECT ts.id AS ts_id, ts.week_start, ts.updated_at, u.full_name "
                            + "  FROM timesheets ts "
                            + "  JOIN candidates c ON c.id = ts.intern_id "
                            + "  JOIN users u ON u.id = c.user_id "
                            + " WHERE ts.status = 'VERIFIED' "
                            + " ORDER BY ts.updated_at ASC NULLS LAST "
                            + " LIMIT ?", TOP_N)) {
                UUID tsId = uuid(r.get("ts_id"));
                String key = "MANAGER_TIMESHEET_APPROVE:" + tsId;
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
                "MANAGER_TIMESHEET_APPROVE",
                "Timesheets to approve",
                "clock",
                count,
                actionUrl,
                severity(count, 10, 3),
                items);
    }

    private TodoBucket weeklyReportsToApprove(Set<String> dismissed) {
        String actionUrl = "/careers/manager/weekly-reports";
        long count = countOrZero(
                "SELECT COUNT(*) FROM weekly_reports wr WHERE wr.status = 'VERIFIED'");
        List<TodoItem> items = new ArrayList<>();
        if (count > 0) {
            for (Map<String, Object> r : safeQueryForList(
                    "SELECT wr.id AS wr_id, wr.week_start, wr.verified_at, u.full_name "
                            + "  FROM weekly_reports wr "
                            + "  JOIN candidates c ON c.id = wr.intern_id "
                            + "  JOIN users u ON u.id = c.user_id "
                            + " WHERE wr.status = 'VERIFIED' "
                            + " ORDER BY wr.verified_at ASC NULLS LAST "
                            + " LIMIT ?", TOP_N)) {
                UUID wrId = uuid(r.get("wr_id"));
                String key = "MANAGER_WEEKLY_REPORT_APPROVE:" + wrId;
                items.add(new TodoItem(
                        key,
                        strOrDash(r.get("full_name")),
                        "Week of " + strOrDash(r.get("week_start")),
                        actionUrl,
                        instantOf(r.get("verified_at")),
                        dismissed.contains(key)));
            }
        }
        return new TodoBucket(
                "MANAGER_WEEKLY_REPORT_APPROVE",
                "Weekly reports to approve",
                "file-text",
                count,
                actionUrl,
                severity(count, 10, 3),
                items);
    }

    private TodoBucket recordingApprovals(Set<String> dismissed) {
        String actionUrl = "/careers/manager/recording-approvals";
        long count = countOrZero(
                "SELECT COUNT(*) FROM intern_evaluations ie "
                        + " WHERE ie.recording_document_id IS NOT NULL "
                        + "   AND ie.recording_approval_status = 'PENDING_APPROVAL'");
        List<TodoItem> items = new ArrayList<>();
        if (count > 0) {
            for (Map<String, Object> r : safeQueryForList(
                    "SELECT ie.id AS ie_id, ie.evaluation_type, ie.updated_at, "
                            + "       u.full_name "
                            + "  FROM intern_evaluations ie "
                            + "  JOIN users u ON u.id = ie.intern_id "
                            + " WHERE ie.recording_document_id IS NOT NULL "
                            + "   AND ie.recording_approval_status = 'PENDING_APPROVAL' "
                            + " ORDER BY ie.updated_at ASC NULLS LAST "
                            + " LIMIT ?", TOP_N)) {
                UUID ieId = uuid(r.get("ie_id"));
                String key = "MANAGER_RECORDING_APPROVAL:" + ieId;
                items.add(new TodoItem(
                        key,
                        strOrDash(r.get("full_name")),
                        strOrDash(r.get("evaluation_type")),
                        actionUrl,
                        instantOf(r.get("updated_at")),
                        dismissed.contains(key)));
            }
        }
        return new TodoBucket(
                "MANAGER_RECORDING_APPROVAL",
                "Recording approvals",
                "video",
                count,
                actionUrl,
                severity(count, 5, 1),
                items);
    }

    // ── Helpers ────────────────────────────────────────────────────────

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
            log.debug("[ManagerTodo] count fallback ({}): {}", sql, e.getMessage());
            return 0L;
        }
    }

    private List<Map<String, Object>> safeQueryForList(String sql, Object... params) {
        try {
            return jdbc.queryForList(sql, params);
        } catch (Exception e) {
            log.debug("[ManagerTodo] queryForList fallback ({}): {}", sql, e.getMessage());
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
