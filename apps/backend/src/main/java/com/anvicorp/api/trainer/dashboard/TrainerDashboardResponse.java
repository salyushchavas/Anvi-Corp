package com.anvicorp.api.trainer.dashboard;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Trainer Phase 1 — Home dashboard response. Single call returns the
 * 6 KPI snapshots, today's meetings, recent activity, and unread
 * notification count. Mirrors the ERM dashboard shape for frontend
 * consistency.
 */
public record TrainerDashboardResponse(
        Caller caller,
        Instant asOf,
        Map<TrainerKpiKey, KpiSnapshot> kpis,
        /** "This week" focus strip — actionable workload condensed into
         *  ≤5 clickable items the trainer can triage in a glance. Zero
         *  items are still returned so the frontend can render "all
         *  caught up" deterministically. */
        List<FocusItem> focusItems,
        List<TodayMeetingRow> todayMeetings,
        List<RecentActivityRow> recentActivity,
        long unreadNotifications
) {

    public record Caller(String firstName, String lastName, String role) {}

    /** One actionable workload item for the trainer-home focus strip.
     *  Frontend hides items with {@code count == 0}; if every item is
     *  zero, the strip renders an "all caught up" pill instead. */
    public record FocusItem(
            String key,
            String label,
            long count,
            String actionUrl
    ) {}

    public record KpiSnapshot(
            TrainerKpiKey key,
            String label,
            long count,
            long urgentCount,
            String helperText,
            String actionUrl
    ) {}

    public record TodayMeetingRow(
            UUID meetingId,
            UUID internLifecycleId,
            String internName,
            Instant scheduledFor,
            Integer durationMinutes,
            String topic,
            /** Host link — present only when caller hosts the meeting. */
            String zoomStartUrl,
            String zoomJoinUrl
    ) {}

    public record RecentActivityRow(
            Instant at,
            String entityType,
            UUID entityId,
            String action,
            UUID actorUserId,
            String actorName,
            UUID subjectUserId,
            String subjectName,
            String deepLink
    ) {}
}
