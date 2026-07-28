package com.anvicorp.api.manager;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.InternLifecycleStatus;
import com.anvicorp.api.enums.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Manager Phase 1 — Executive Overview aggregates. Portfolio-wide for
 *  MANAGER + SUPER_ADMIN; pre-conversion applicants have no
 *  {@code manager_id} so per-manager scoping would zero out the funnel.
 *
 *  <p>Two GROUP-BY queries (one against {@code users.lifecycle_status},
 *  one against {@code applications.status}) plus three small counts —
 *  cheaper than the eight-call per-card pattern ERM uses, and gives the
 *  UI all 13 lifecycle buckets in a single round-trip.</p>
 *
 *  <p>The lifecycle map is the source of truth that has to reconcile with
 *  every other dashboard ({@code users.lifecycle_status} is the canonical
 *  funnel position per {@link InternLifecycleStatus}).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerDashboardService {

    private static final Set<String> APPLICANT_PIPELINE_STAGES = Set.of(
            "APPLIED", "HOLD", "INFO_REQUESTED",
            "SCREENING_SENT", "SCREENING_COMPLETED", "SHORTLISTED",
            "INTERVIEW_SCHEDULED", "INTERVIEWED", "SELECTED_CONDITIONAL",
            "OFFERED");

    private final JdbcTemplate jdbc;

    public ManagerDtos.OverviewResponse getOverview(User caller) {
        boolean superAdmin = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);

        Map<String, Long> lifecycleCounts = zeroFilledLifecycleMap();
        try {
            jdbc.query(
                    "SELECT lifecycle_status AS s, COUNT(*) AS n "
                            + "  FROM users "
                            + " WHERE lifecycle_status IS NOT NULL "
                            + " GROUP BY lifecycle_status",
                    rs -> {
                        lifecycleCounts.put(rs.getString("s"), rs.getLong("n"));
                    });
        } catch (Exception e) {
            log.warn("[ManagerDashboard] lifecycle GROUP BY failed: {}", e.getMessage());
        }

        Map<String, Long> applicationCounts = new LinkedHashMap<>();
        try {
            jdbc.query(
                    "SELECT status AS s, COUNT(*) AS n "
                            + "  FROM applications "
                            + " GROUP BY status",
                    rs -> {
                        applicationCounts.put(rs.getString("s"), rs.getLong("n"));
                    });
        } catch (Exception e) {
            log.warn("[ManagerDashboard] application GROUP BY failed: {}", e.getMessage());
        }

        ManagerDtos.HeadlineBuckets buckets = computeBuckets(lifecycleCounts, applicationCounts);
        ManagerDtos.ConversionKpis kpis = computeKpis(applicationCounts);

        // Match ManagerHireApprovalService.list() WHERE clause exactly so
        // the top-of-page KPI never disagrees with the queue's own
        // "N awaiting your decision" count.
        long pendingHireApprovals = safeOfferCount(
                "SELECT COUNT(*) FROM interviews iv "
                        + " WHERE iv.status = 'COMPLETED' "
                        + "   AND iv.manager_hire_decision IN ('PENDING','HOLD')");

        return new ManagerDtos.OverviewResponse(
                new ManagerDtos.CallerView(
                        caller.getId(),
                        caller.getFullName(),
                        caller.getEmail(),
                        superAdmin ? "SUPER_ADMIN" : "MANAGER",
                        superAdmin),
                lifecycleCounts,
                applicationCounts,
                buckets,
                kpis,
                pendingHireApprovals,
                Instant.now());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private Map<String, Long> zeroFilledLifecycleMap() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (InternLifecycleStatus s : InternLifecycleStatus.values()) {
            m.put(s.name(), 0L);
        }
        return m;
    }

    private ManagerDtos.HeadlineBuckets computeBuckets(
            Map<String, Long> lifecycle, Map<String, Long> applications) {
        long totalApplications = applications.values().stream().mapToLong(Long::longValue).sum();
        long applicantsInPipeline = APPLICANT_PIPELINE_STAGES.stream()
                .mapToLong(s -> applications.getOrDefault(s, 0L))
                .sum();
        long offersAwaitingSignature = safeOfferCount("SELECT COUNT(*) FROM offers WHERE status = 'SENT'");
        long prospectiveNewHires = sumLifecycle(lifecycle,
                InternLifecycleStatus.OFFER_SIGNED,
                InternLifecycleStatus.EMPLOYEE_ID_CREATED,
                InternLifecycleStatus.ONBOARDING_ASSIGNED,
                InternLifecycleStatus.ONBOARDING_ACCEPTED);
        long activeInterns = lifecycle.getOrDefault(
                InternLifecycleStatus.ACTIVE_INTERN.name(), 0L);
        long inactiveInterns = lifecycle.getOrDefault(
                InternLifecycleStatus.INACTIVE_INTERN.name(), 0L);
        return new ManagerDtos.HeadlineBuckets(
                totalApplications, applicantsInPipeline, offersAwaitingSignature,
                prospectiveNewHires, activeInterns, inactiveInterns);
    }

    private ManagerDtos.ConversionKpis computeKpis(Map<String, Long> applications) {
        long applied = sumApplications(applications, APPLICANT_PIPELINE_STAGES)
                + applications.getOrDefault("ACCEPTED", 0L);
        long interviewOrLater = sumApplications(applications, Set.of(
                "INTERVIEW_SCHEDULED", "INTERVIEWED",
                "SELECTED_CONDITIONAL", "OFFERED", "ACCEPTED"));
        long scheduledOrLater = sumApplications(applications, Set.of(
                "INTERVIEW_SCHEDULED", "INTERVIEWED",
                "SELECTED_CONDITIONAL", "OFFERED", "ACCEPTED"));
        long interviewed = sumApplications(applications, Set.of(
                "INTERVIEWED", "SELECTED_CONDITIONAL", "OFFERED", "ACCEPTED"));
        long offered = sumApplications(applications, Set.of("OFFERED", "ACCEPTED"));
        long accepted = applications.getOrDefault("ACCEPTED", 0L);

        long offersPendingOver7Days = safeOfferCount(
                "SELECT COUNT(*) FROM offers "
                        + " WHERE status = 'SENT' "
                        + "   AND sent_at IS NOT NULL "
                        + "   AND sent_at < NOW() - INTERVAL '7 days'");

        return new ManagerDtos.ConversionKpis(
                pct(interviewOrLater, applied),
                pct(interviewed, scheduledOrLater),
                pct(accepted, offered),
                offersPendingOver7Days);
    }

    private long sumLifecycle(Map<String, Long> m, InternLifecycleStatus... keys) {
        long total = 0;
        for (InternLifecycleStatus k : keys) total += m.getOrDefault(k.name(), 0L);
        return total;
    }

    private long sumApplications(Map<String, Long> m, Set<String> keys) {
        long total = 0;
        for (String k : keys) total += m.getOrDefault(k, 0L);
        return total;
    }

    private Double pct(long numerator, long denominator) {
        if (denominator <= 0) return null;
        return Math.round((numerator * 1000.0) / denominator) / 10.0;
    }

    private long safeOfferCount(String sql) {
        try {
            Long v = jdbc.queryForObject(sql, Long.class);
            return v != null ? v : 0L;
        } catch (Exception e) {
            log.warn("[ManagerDashboard] {} failed: {}", sql, e.getMessage());
            return 0L;
        }
    }
}
