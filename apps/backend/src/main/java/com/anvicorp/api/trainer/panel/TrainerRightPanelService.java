package com.anvicorp.api.trainer.panel;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.trainer.dashboard.TrainerThresholds;
import com.anvicorp.api.trainer.panel.TrainerRightPanelResponse.Alert;
import com.anvicorp.api.trainer.panel.TrainerRightPanelResponse.QuickAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Trainer Phase 1 — right-side panel: 5 doc §4 quick actions + 2
 * alerts + today's meeting count + unread notification count. Counts
 * are live (no cache) so the badges feel responsive; the queries are
 * indexed + bounded.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrainerRightPanelService {

    private static final ZoneId ZONE = ZoneId.of(TrainerThresholds.DEFAULT_TIMEZONE);

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public TrainerRightPanelResponse build(User caller) {
        if (caller == null) throw new ForbiddenException("Caller required");
        if (!caller.getRoles().contains(UserRole.TRAINER)
                && !caller.getRoles().contains(UserRole.SUPER_ADMIN)) {
            throw new ForbiddenException("TRAINER or SUPER_ADMIN required");
        }
        UUID callerId = caller.getId();
        // Org-wide trainer model: every TRAINER (there is one) + SUPER_ADMIN
        // sees ALL active interns on the KPI counters. The previous
        // per-intern `il.trainer_id = caller.id` fence read 0 for any intern
        // whose trainer_id hadn't been stamped — which is the default state
        // for ACTIVE interns under the single-trainer-org config.
        String scope = "";
        Object[] scopeArg = new Object[]{};
        String currentMonth = YearMonth.now(ZONE).toString();

        long withoutProject = safeCount(
                "SELECT COUNT(*) FROM intern_lifecycles il "
                        + " WHERE il.active_status = 'ACTIVE' "
                        + scope
                        + " AND NOT EXISTS (SELECT 1 FROM projects p "
                        + "                    WHERE p.intern_lifecycle_id = il.id "
                        + "                      AND p.month_year = ? "
                        + "                      AND p.status <> 'CANCELLED')",
                append(scopeArg, currentMonth));

        long noUpcomingMeeting = safeCount(
                "SELECT COUNT(*) FROM intern_lifecycles il "
                        + " WHERE il.active_status = 'ACTIVE' "
                        + scope
                        + " AND NOT EXISTS (SELECT 1 FROM weekly_meetings wm "
                        + "                    WHERE wm.intern_lifecycle_id = il.id "
                        + "                      AND wm.status = 'SCHEDULED' "
                        + "                      AND wm.scheduled_for > NOW() "
                        + "                      AND wm.scheduled_for < NOW() + INTERVAL '7 days')",
                scopeArg);

        long overdueSubmissions = safeCount(
                "SELECT COUNT(*) FROM projects p "
                        + "  JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                        + " WHERE p.status = 'SUBMITTED' "
                        + "   AND p.reviewed_at IS NULL "
                        + "   AND p.submitted_at < NOW() - INTERVAL '48 hours' "
                        + scope,
                scopeArg);

        long submissionsPendingReview = safeCount(
                "SELECT COUNT(*) FROM projects p "
                        + "  JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                        + " WHERE p.status = 'SUBMITTED' "
                        + "   AND p.reviewed_at IS NULL "
                        + scope,
                scopeArg);

        // Trainer Phase 3 — submissions where the trainer hasn't recorded a
        // terminal decision yet (NULL trainer_decision). Drives the new
        // /pending-reviews queue + "Publish feedback" quick-action badge.
        long pendingFeedbackPublish = safeCount(
                "SELECT COUNT(*) FROM project_submissions s "
                        + "  JOIN projects p ON p.id = s.project_id "
                        + "  JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                        + " WHERE s.trainer_decision IS NULL "
                        + scope,
                scopeArg);

        long missingWeeklyMeeting = safeCount(
                "SELECT COUNT(*) FROM intern_lifecycles il "
                        + " WHERE il.active_status = 'ACTIVE' "
                        + scope
                        + " AND NOT EXISTS (SELECT 1 FROM weekly_meetings wm "
                        + "                    WHERE wm.intern_lifecycle_id = il.id "
                        + "                      AND wm.scheduled_for > NOW() - INTERVAL '"
                        + TrainerThresholds.MEETING_MISSING_DAYS + " days' "
                        + "                      AND wm.status IN ('SCHEDULED','COMPLETED'))",
                scopeArg);

        List<QuickAction> quickActions = new ArrayList<>();
        // Trainer Phase 2 + 3 — assign-project / schedule-meeting /
        // request-revision / publish-feedback all live; only send-reminder
        // stays deferred until Phase 4.
        quickActions.add(new QuickAction("assign-project", "Assign project",
                "/careers/trainer/assign-project", withoutProject, false));
        quickActions.add(new QuickAction("schedule-meeting", "Schedule meeting",
                "/careers/trainer/weekly-meetings", noUpcomingMeeting, false));
        quickActions.add(new QuickAction("send-reminder", "Send reminder",
                "/careers/trainer/active-interns?filter=overdue",
                overdueSubmissions + missingWeeklyMeeting, true));
        quickActions.add(new QuickAction("request-revision", "Request revision",
                "/careers/trainer/pending-reviews",
                submissionsPendingReview, false));
        quickActions.add(new QuickAction("publish-feedback", "Publish feedback",
                "/careers/trainer/pending-reviews?filter=ready",
                pendingFeedbackPublish, false));

        List<Alert> alerts = new ArrayList<>();
        alerts.add(new Alert("missing-weekly-meeting",
                "Missing weekly meeting", missingWeeklyMeeting,
                missingWeeklyMeeting > 0 ? "WARN" : "INFO"));
        alerts.add(new Alert("pending-review",
                "Pending review", submissionsPendingReview,
                submissionsPendingReview > 5 ? "URGENT"
                        : submissionsPendingReview > 0 ? "WARN" : "INFO"));

        long unread = safeCount(
                "SELECT COUNT(*) FROM user_notifications "
                        + " WHERE recipient_user_id = ? AND read_at IS NULL",
                new Object[]{callerId});

        long todayMeetings = safeCount(
                "SELECT COUNT(*) FROM weekly_meetings wm "
                        + "  JOIN intern_lifecycles il ON il.id = wm.intern_lifecycle_id "
                        + " WHERE wm.status = 'SCHEDULED' "
                        + "   AND wm.scheduled_for::date = CURRENT_DATE "
                        + scope,
                scopeArg);

        return new TrainerRightPanelResponse(
                quickActions, alerts, unread, todayMeetings);
    }

    private long safeCount(String sql, Object[] params) {
        try {
            Long c = jdbc.queryForObject(sql, Long.class, params);
            return c == null ? 0L : c;
        } catch (Exception e) {
            log.debug("[TrainerRightPanel] count fallback: {}", e.getMessage());
            return 0L;
        }
    }

    private static Object[] append(Object[] base, Object tail) {
        Object[] out = new Object[base.length + 1];
        System.arraycopy(base, 0, out, 0, base.length);
        out[base.length] = tail;
        return out;
    }
}
