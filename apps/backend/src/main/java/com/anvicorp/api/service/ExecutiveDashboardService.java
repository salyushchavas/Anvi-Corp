package com.anvicorp.api.service;

import com.anvicorp.api.dto.executive.ComplianceHealthResponse;
import com.anvicorp.api.dto.executive.ExecutiveDashboardResponse;
import com.anvicorp.api.dto.executive.HiringFunnelResponse;
import com.anvicorp.api.dto.executive.InternProgramResponse;
import com.anvicorp.api.dto.executive.SupervisorLoadRowResponse;
import com.anvicorp.api.dto.executive.WeeklyCycleHealthResponse;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.EVerifyCase;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.I983Plan;
import com.anvicorp.api.entity.I9Form;
import com.anvicorp.api.entity.Timesheet;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.entity.WeeklyReport;
import com.anvicorp.api.enums.ApplicationStatus;
import com.anvicorp.api.enums.EVerifyStatus;
import com.anvicorp.api.enums.EngagementStatus;
import com.anvicorp.api.enums.I9Status;
import com.anvicorp.api.enums.TimesheetStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.enums.WeeklyReportStatus;
import com.anvicorp.api.enums.EvaluationStatus;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.EVerifyCaseRepository;
import com.anvicorp.api.repository.EngagementRepository;
import com.anvicorp.api.repository.EvaluationRepository;
import com.anvicorp.api.repository.I983PlanRepository;
import com.anvicorp.api.repository.I9FormRepository;
import com.anvicorp.api.repository.TimesheetRepository;
import com.anvicorp.api.repository.WeeklyReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Aggregate read for the Executive (leadership) dashboard. Single read-only
 * transaction; returns counts + rates only. No PII, no mutation, no
 * supplementary "actions" — leadership oversight, not a to-do list.
 *
 * <h2>Funnel stages</h2>
 * Same five bands the operations dashboard uses, but the executive view
 * includes stage-to-stage conversion rates so leadership can read the
 * pinch points.
 *
 * <h2>"At-risk" definition</h2>
 * Active interns who EITHER missed this week's report (no row exists yet)
 * OR have a report in RETURNED state that hasn't been resubmitted. This is
 * the cheap, signal-y proxy — leadership can ask Operations to dig in.
 *
 * <h2>"Cleared" definition</h2>
 * Active interns whose I-9 is COMPLETED. We intentionally don't layer
 * E-Verify / I-983 into the single "cleared" number — those are surfaced
 * separately as their own completion rates so leadership sees the layers.
 *
 * <h2>Authorization expiry window</h2>
 * 90 days, matching the HR dashboard's headline action item.
 */
@Service
@RequiredArgsConstructor
public class ExecutiveDashboardService {

    private static final int EXPIRY_WINDOW_DAYS = 90;
    private static final int SUPERVISOR_LOAD_LIMIT = 20;

    // Application-status bands match operations dashboard funnel bands.
    private static final Set<ApplicationStatus> APPLIED_BAND =
            EnumSet.of(ApplicationStatus.APPLIED);
    private static final Set<ApplicationStatus> SCREENING_BAND = EnumSet.of(
            ApplicationStatus.SCREENING_SENT,
            ApplicationStatus.SCREENING_COMPLETED,
            ApplicationStatus.SHORTLISTED);
    private static final Set<ApplicationStatus> INTERVIEW_BAND = EnumSet.of(
            ApplicationStatus.INTERVIEW_SCHEDULED,
            ApplicationStatus.INTERVIEWED);
    private static final Set<ApplicationStatus> OFFER_BAND = EnumSet.of(
            ApplicationStatus.SELECTED_CONDITIONAL,
            ApplicationStatus.OFFERED,
            ApplicationStatus.ACCEPTED);

    private static final Set<EngagementStatus> ACTIVE_ONLY =
            EnumSet.of(EngagementStatus.ACTIVE);

    private final ApplicationRepository applicationRepository;
    private final EngagementRepository engagementRepository;
    private final I9FormRepository i9FormRepository;
    private final I983PlanRepository i983PlanRepository;
    private final EVerifyCaseRepository everifyCaseRepository;
    private final WeeklyReportRepository weeklyReportRepository;
    private final TimesheetRepository timesheetRepository;
    private final EvaluationRepository evaluationRepository;

    @Transactional(readOnly = true)
    public ExecutiveDashboardResponse build(User caller) {
        String operatorName = caller != null ? caller.getFullName() : null;
        boolean isSuperAdmin = caller != null && caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);

        HiringFunnelResponse funnel = buildHiringFunnel();
        InternProgramResponse program = buildInternProgram();
        ComplianceHealthResponse compliance = buildComplianceHealth();
        WeeklyCycleHealthResponse weekly = buildWeeklyCycleHealth();
        List<SupervisorLoadRowResponse> supervisorLoad = buildSupervisorLoad();

        return ExecutiveDashboardResponse.builder()
                .operatorName(operatorName)
                .isSuperAdminView(isSuperAdmin)
                .hiringFunnel(funnel)
                .internProgram(program)
                .complianceHealth(compliance)
                .weeklyCycle(weekly)
                .supervisorLoad(supervisorLoad)
                .build();
    }

    // ── Funnel ──────────────────────────────────────────────────────────────

    private HiringFunnelResponse buildHiringFunnel() {
        long applied = sumStatusCounts(APPLIED_BAND);
        long screening = sumStatusCounts(SCREENING_BAND);
        long interview = sumStatusCounts(INTERVIEW_BAND);
        long offer = sumStatusCounts(OFFER_BAND);
        // Hired is engagement-side now (Phase 3).
        long hired = engagementRepository.countByStatusIn(EnumSet.of(
                EngagementStatus.PENDING_COMPLIANCE,
                EngagementStatus.READY_TO_START,
                EngagementStatus.ACTIVE,
                EngagementStatus.COMPLETED));

        return HiringFunnelResponse.builder()
                .applied(applied)
                .screening(screening)
                .interview(interview)
                .offer(offer)
                .hired(hired)
                .appliedToScreening(rate(screening, applied))
                .screeningToInterview(rate(interview, screening))
                .interviewToOffer(rate(offer, interview))
                .offerToHired(rate(hired, offer))
                .overall(rate(hired, applied))
                .build();
    }

    private long sumStatusCounts(Set<ApplicationStatus> statuses) {
        long total = 0L;
        for (ApplicationStatus s : statuses) {
            total += applicationRepository.countByStatus(s);
        }
        return total;
    }

    // ── Intern program ──────────────────────────────────────────────────────

    private InternProgramResponse buildInternProgram() {
        long active = engagementRepository.countByStatus(EngagementStatus.ACTIVE);
        long completed = engagementRepository.countByStatus(EngagementStatus.COMPLETED);
        long terminated = engagementRepository.countByStatus(EngagementStatus.TERMINATED);
        long blocked = engagementRepository.countByStatus(
                EngagementStatus.BLOCKED_NO_AUTHORIZATION);
        long atRisk = computeAtRiskCount();

        long evaluationsFinalized = evaluationRepository.countByStatus(EvaluationStatus.FINALIZED);
        long evaluationsInDraft = evaluationRepository.countByStatus(EvaluationStatus.DRAFT);
        Double avgRating = evaluationRepository.averageFinalizedOverallRating();

        return InternProgramResponse.builder()
                .activeInterns(active)
                .completedInterns(completed)
                .terminatedInterns(terminated)
                .blockedInterns(blocked)
                .completionRate(rate(completed, completed + terminated))
                .atRiskCount(atRisk)
                .evaluationsFinalizedCount(evaluationsFinalized)
                .evaluationsInDraftCount(evaluationsInDraft)
                .averageEvaluationRating(avgRating)
                .build();
    }

    /**
     * Active interns missing this week's report OR with a RETURNED-but-not-
     * resubmitted report. Cheap proxy — refine when the data set grows.
     */
    private long computeAtRiskCount() {
        LocalDate weekStart = currentWeekStart();
        List<Engagement> active = engagementRepository.findByStatus(EngagementStatus.ACTIVE);
        long atRisk = 0L;
        for (Engagement e : active) {
            if (e.getCandidate() == null || e.getCandidate().getId() == null) continue;
            WeeklyReport report = weeklyReportRepository
                    .findByInternIdAndWeekStart(e.getCandidate().getId(), weekStart)
                    .orElse(null);
            if (report == null || report.getStatus() == WeeklyReportStatus.RETURNED) {
                atRisk++;
            }
        }
        return atRisk;
    }

    // ── Compliance health ───────────────────────────────────────────────────

    private ComplianceHealthResponse buildComplianceHealth() {
        List<Engagement> active = engagementRepository.findByStatus(EngagementStatus.ACTIVE);
        long activeInternsTotal = active.size();

        long clearedCount = 0L;
        long i9CompletedCount = 0L;
        long i9TotalCount = 0L;
        long everifyAuthorizedCount = 0L;
        long everifyTotalCount = 0L;
        Set<UUID> expiringCandidateIds = new HashSet<>();

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate cutoff = today.plusDays(EXPIRY_WINDOW_DAYS);

        for (Engagement e : active) {
            if (e.getCandidate() == null || e.getCandidate().getId() == null) continue;
            UUID candidateId = e.getCandidate().getId();

            // I-9
            I9Form i9 = i9FormRepository.findByCandidateId(candidateId).orElse(null);
            if (i9 != null) {
                i9TotalCount++;
                if (i9.getStatus() == I9Status.COMPLETED) {
                    i9CompletedCount++;
                    clearedCount++;
                }
                if (i9.getWorkAuthExpirationDate() != null) {
                    LocalDate d = i9.getWorkAuthExpirationDate();
                    if (!d.isBefore(today) && !d.isAfter(cutoff)) {
                        expiringCandidateIds.add(candidateId);
                    }
                }
            }

            // E-Verify (only when an I-9 exists; case is keyed off the I-9).
            if (i9 != null) {
                EVerifyCase ev = everifyCaseRepository.findByI9FormId(i9.getId()).orElse(null);
                if (ev != null) {
                    everifyTotalCount++;
                    if (ev.getStatus() == EVerifyStatus.EMPLOYMENT_AUTHORIZED
                            || ev.getStatus() == EVerifyStatus.CLOSED) {
                        everifyAuthorizedCount++;
                    }
                }
            }

            // I-983 expiry (STEM OPT end-date).
            List<I983Plan> plans = i983PlanRepository
                    .findByCandidateIdOrderByCreatedAtDesc(candidateId);
            if (!plans.isEmpty()) {
                LocalDate optEnd = plans.get(0).getOptEndDate();
                if (optEnd != null && !optEnd.isBefore(today) && !optEnd.isAfter(cutoff)) {
                    expiringCandidateIds.add(candidateId);
                }
            }
        }

        return ComplianceHealthResponse.builder()
                .activeInternsTotal(activeInternsTotal)
                .clearedCount(clearedCount)
                .clearedRate(rate(clearedCount, activeInternsTotal))
                .i9CompletedCount(i9CompletedCount)
                .i9TotalCount(i9TotalCount)
                .i9CompletionRate(rate(i9CompletedCount, i9TotalCount))
                .everifyAuthorizedCount(everifyAuthorizedCount)
                .everifyTotalCount(everifyTotalCount)
                .everifyCompletionRate(rate(everifyAuthorizedCount, everifyTotalCount))
                .authorizationsExpiringSoonCount(expiringCandidateIds.size())
                .build();
    }

    // ── Weekly-cycle health ─────────────────────────────────────────────────

    private WeeklyCycleHealthResponse buildWeeklyCycleHealth() {
        LocalDate weekStart = currentWeekStart();
        LocalDate lastWeekStart = weekStart.minusDays(7);
        List<Engagement> active = engagementRepository.findByStatus(EngagementStatus.ACTIVE);
        long activeInternsThisWeek = active.size();

        long reportsSubmittedThisWeek = 0L;
        long reportsAwaitingApproval = 0L;
        long timesheetsSubmittedThisWeek = 0L;
        long timesheetsAwaitingApproval = 0L;
        long overdueReportsLastWeek = 0L;
        long overdueTimesheetsLastWeek = 0L;

        for (Engagement e : active) {
            if (e.getCandidate() == null || e.getCandidate().getId() == null) continue;
            UUID candidateId = e.getCandidate().getId();

            WeeklyReport thisWeekReport = weeklyReportRepository
                    .findByInternIdAndWeekStart(candidateId, weekStart).orElse(null);
            if (thisWeekReport != null) {
                WeeklyReportStatus rs = thisWeekReport.getStatus();
                if (rs == WeeklyReportStatus.SUBMITTED
                        || rs == WeeklyReportStatus.APPROVED) {
                    reportsSubmittedThisWeek++;
                }
                if (rs == WeeklyReportStatus.SUBMITTED) {
                    reportsAwaitingApproval++;
                }
            }
            WeeklyReport lastWeekReport = weeklyReportRepository
                    .findByInternIdAndWeekStart(candidateId, lastWeekStart).orElse(null);
            if (lastWeekReport == null) {
                overdueReportsLastWeek++;
            } else if (lastWeekReport.getStatus() == WeeklyReportStatus.DRAFT
                    || lastWeekReport.getStatus() == WeeklyReportStatus.RETURNED) {
                overdueReportsLastWeek++;
            }

            List<Timesheet> sheets = timesheetRepository.findForIntern(candidateId);
            Timesheet thisWeekSheet = sheets.stream()
                    .filter(t -> weekStart.equals(t.getWeekStart()))
                    .findFirst().orElse(null);
            if (thisWeekSheet != null) {
                TimesheetStatus ts = thisWeekSheet.getStatus();
                if (ts == TimesheetStatus.SUBMITTED || ts == TimesheetStatus.APPROVED) {
                    timesheetsSubmittedThisWeek++;
                }
                if (ts == TimesheetStatus.SUBMITTED) {
                    timesheetsAwaitingApproval++;
                }
            }
            Timesheet lastWeekSheet = sheets.stream()
                    .filter(t -> lastWeekStart.equals(t.getWeekStart()))
                    .findFirst().orElse(null);
            if (lastWeekSheet == null) {
                overdueTimesheetsLastWeek++;
            } else if (lastWeekSheet.getStatus() == TimesheetStatus.DRAFT
                    || lastWeekSheet.getStatus() == TimesheetStatus.REJECTED) {
                overdueTimesheetsLastWeek++;
            }
        }

        return WeeklyCycleHealthResponse.builder()
                .activeInternsThisWeek(activeInternsThisWeek)
                .reportsSubmittedThisWeek(reportsSubmittedThisWeek)
                .reportSubmissionRate(rate(reportsSubmittedThisWeek, activeInternsThisWeek))
                .reportsAwaitingApproval(reportsAwaitingApproval)
                .timesheetsSubmittedThisWeek(timesheetsSubmittedThisWeek)
                .timesheetSubmissionRate(rate(timesheetsSubmittedThisWeek, activeInternsThisWeek))
                .timesheetsAwaitingApproval(timesheetsAwaitingApproval)
                .overdueReportsLastWeek(overdueReportsLastWeek)
                .overdueTimesheetsLastWeek(overdueTimesheetsLastWeek)
                .build();
    }

    // ── Supervisor load ─────────────────────────────────────────────────────

    private List<SupervisorLoadRowResponse> buildSupervisorLoad() {
        LocalDate weekStart = currentWeekStart();
        List<Engagement> active = engagementRepository.findByStatus(EngagementStatus.ACTIVE);

        // Group active engagements by supervisor.id; engagements with a null
        // supervisor accumulate under a synthetic "Unassigned" row so leadership
        // sees the gap.
        Map<UUID, SupervisorAggregate> bySupervisor = new HashMap<>();
        UUID unassignedKey = new UUID(0L, 0L);
        for (Engagement e : active) {
            User sup = e.getSupervisor();
            UUID supId = sup != null ? sup.getId() : unassignedKey;
            String supName = sup != null ? sup.getFullName() : "Unassigned";
            SupervisorAggregate agg = bySupervisor.computeIfAbsent(
                    supId, k -> new SupervisorAggregate(supId, supName));
            agg.activeInterns++;
            if (e.getCandidate() == null || e.getCandidate().getId() == null) continue;
            UUID candidateId = e.getCandidate().getId();

            WeeklyReport thisWeekReport = weeklyReportRepository
                    .findByInternIdAndWeekStart(candidateId, weekStart).orElse(null);
            if (thisWeekReport != null
                    && thisWeekReport.getStatus() == WeeklyReportStatus.SUBMITTED) {
                agg.pendingReports++;
            }

            Timesheet thisWeekSheet = timesheetRepository.findForIntern(candidateId).stream()
                    .filter(t -> weekStart.equals(t.getWeekStart()))
                    .findFirst().orElse(null);
            if (thisWeekSheet != null
                    && thisWeekSheet.getStatus() == TimesheetStatus.SUBMITTED) {
                agg.pendingTimesheets++;
            }
        }

        List<SupervisorLoadRowResponse> rows = new ArrayList<>(bySupervisor.size());
        for (SupervisorAggregate a : bySupervisor.values()) {
            rows.add(SupervisorLoadRowResponse.builder()
                    .supervisorUserId(a.id.equals(unassignedKey) ? null : a.id)
                    .supervisorName(a.name)
                    .activeInterns(a.activeInterns)
                    .pendingReports(a.pendingReports)
                    .pendingTimesheets(a.pendingTimesheets)
                    .build());
        }
        // Sort by total work descending, name ascending as tiebreaker.
        rows.sort(Comparator
                .comparingLong((SupervisorLoadRowResponse r) ->
                        r.getPendingReports() + r.getPendingTimesheets() + r.getActiveInterns())
                .reversed()
                .thenComparing(SupervisorLoadRowResponse::getSupervisorName,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        if (rows.size() > SUPERVISOR_LOAD_LIMIT) {
            rows = rows.subList(0, SUPERVISOR_LOAD_LIMIT);
        }
        return rows;
    }

    /** Mutable accumulator — service-private, doesn't escape. */
    private static class SupervisorAggregate {
        final UUID id;
        final String name;
        long activeInterns;
        long pendingReports;
        long pendingTimesheets;
        SupervisorAggregate(UUID id, String name) { this.id = id; this.name = name; }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static Double rate(long numerator, long denominator) {
        if (denominator <= 0) return null;
        return ((double) numerator) / ((double) denominator);
    }

    private static LocalDate currentWeekStart() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int back = today.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue();
        if (back < 0) back += 7;
        return today.minusDays(back);
    }
}
