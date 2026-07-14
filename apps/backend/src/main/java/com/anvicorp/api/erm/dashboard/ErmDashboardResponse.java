package com.anvicorp.api.erm.dashboard;

import com.anvicorp.api.erm.exception.ExceptionRow;
import com.anvicorp.api.erm.exception.ExceptionType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Phase 1 — the full ERM Home payload. Returned by
 * {@code GET /api/v1/erm/dashboard}; nested records can be re-fetched
 * independently in Phase 6 without re-running the full assembly.
 */
public record ErmDashboardResponse(
        Caller caller,
        Instant asOf,
        String scope,
        Map<ErmKpiKey, KpiSnapshot> kpis,
        ExceptionSummary exceptions,
        List<ActivityEntry> recentActivity,
        long unreadNotifications
) {

    public record Caller(
            String firstName,
            String lastName,
            String role
    ) {}

    public record ExceptionSummary(
            Map<ExceptionType, Integer> counts,
            List<ExceptionRow> topUrgent
    ) {}

    public record ActivityEntry(
            String actorName,
            String action,
            String subjectName,
            Instant timestamp,
            String deepLink
    ) {}
}
