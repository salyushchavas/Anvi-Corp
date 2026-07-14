package com.anvicorp.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.dto.supervision.UserSupervisionResponse;
import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.EVerifyCase;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.EvaluationSession;
import com.anvicorp.api.entity.I983Plan;
import com.anvicorp.api.entity.I9Form;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.Timesheet;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.entity.WeeklyReport;
import com.anvicorp.api.enums.EngagementStatus;
import com.anvicorp.api.enums.EvaluationSessionStatus;
import com.anvicorp.api.enums.TimesheetStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.enums.WeeklyReportStatus;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.AuditLogSpecifications;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.EVerifyCaseRepository;
import com.anvicorp.api.repository.EngagementRepository;
import com.anvicorp.api.repository.EvaluationSessionRepository;
import com.anvicorp.api.repository.I983PlanRepository;
import com.anvicorp.api.repository.I9FormRepository;
import com.anvicorp.api.repository.TimesheetRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.repository.WeeklyReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SUPER_ADMIN L3 — consolidated per-user supervision view.
 *
 * <h2>What this service does</h2>
 * Assembles a single read across:
 *   <ul>
 *     <li>profile (UserRepository)</li>
 *     <li>activity timeline (AuditLog scoped to userId)</li>
 *     <li>candidate context — applications / engagement / compliance status /
 *         weekly reports / timesheets / material acks</li>
 *     <li>supervisor context — assigned active interns / review backlog /
 *         upcoming evaluations / published materials count</li>
 *   </ul>
 *
 * <h2>What this service does NOT do</h2>
 * No decrypted PII in the response. The compliance block surfaces
 * STATUS enums + plain LocalDate fields that are already non-encrypted
 * columns (firstDayOfEmployment / workAuthExpirationDate / optEndDate).
 * No SSN, A-Number, document numbers, DOB, or foreign passport ever
 * reach the wire from here. Deep-link {@code href}s point at the
 * existing gated detail pages — opening them goes through those pages'
 * own auth + audit paths.
 *
 * <h2>Audit on view</h2>
 * Every successful build writes one row:
 * {@code action=SUPER_ADMIN_VIEWED_USER}, {@code entity_type=User},
 * {@code entity_id=target.id}, {@code user_id=caller.id}, {@code after_json}
 * carries the target email. Best-effort — failure logs and continues.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserSupervisionService {

    private static final int ACTIVITY_LIMIT = 30;
    private static final int TOP_ACTIONS_LIMIT = 8;
    private static final int CANDIDATE_REPORTS_LIMIT = 12;
    private static final int CANDIDATE_TIMESHEETS_LIMIT = 12;
    private static final int SUPERVISOR_ROSTER_LIMIT = 24;
    /** Audit rows scanned for the top-actions rollup. Capped so the rollup
     *  doesn't load an unbounded slice. */
    private static final int TOP_ACTIONS_SCAN_LIMIT = 500;

    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    // Candidate-side
    private final CandidateRepository candidateRepository;
    private final ApplicationRepository applicationRepository;
    private final EngagementRepository engagementRepository;
    private final WeeklyReportRepository weeklyReportRepository;
    private final TimesheetRepository timesheetRepository;
    // MaterialAcknowledgementRepository removed in Trainer Phase 0.
    private final I9FormRepository i9FormRepository;
    private final I983PlanRepository i983PlanRepository;
    private final EVerifyCaseRepository everifyCaseRepository;

    // Supervisor-side
    // WeeklyMaterialRepository removed in Trainer Phase 0.
    private final EvaluationSessionRepository evaluationSessionRepository;

    // Per-user audit feed (L3 round-2) — resolves the subject-side entity ids
    // so the activity timeline + top-actions reflect actions taken ON the
    // user (e.g. HR approved THIS candidate's I-9), not just actions BY them.
    private final UserAuditEntityResolver userAuditEntityResolver;

    @Transactional
    public UserSupervisionResponse build(UUID targetUserId, User caller) {
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found: " + targetUserId));

        UserSupervisionResponse.ProfileBlock profile = toProfile(target);
        List<UserSupervisionResponse.ActivityRow> activity = buildActivity(target.getId());
        List<UserSupervisionResponse.ActionCount> topActions = buildTopActions(target.getId());
        UserSupervisionResponse.CandidateContext candidateContext = null;
        UserSupervisionResponse.SupervisorContext supervisorContext = null;

        Set<UserRole> roles = target.getRoles() != null
                ? EnumSet.copyOf(target.getRoles())
                : EnumSet.noneOf(UserRole.class);

        if (roles.contains(UserRole.INTERN) || roles.contains(UserRole.INTERN)) {
            candidateContext = buildCandidateContext(target);
        }
        if (roles.contains(UserRole.TRAINER)) {
            supervisorContext = buildSupervisorContext(target);
        }

        // Audit-on-view BEFORE returning — even if the response somehow doesn't
        // reach the caller, the forensic record exists.
        writeViewAudit(caller, target);

        return UserSupervisionResponse.builder()
                .profile(profile)
                .activity(activity)
                .topActions(topActions)
                .candidateContext(candidateContext)
                .supervisorContext(supervisorContext)
                .build();
    }

    // ── Profile ─────────────────────────────────────────────────────────────

    private UserSupervisionResponse.ProfileBlock toProfile(User u) {
        return UserSupervisionResponse.ProfileBlock.builder()
                .id(u.getId())
                .name(u.getFullName())
                .email(u.getEmail())
                .roles(u.getRoles())
                .active(u.getActive() == null ? Boolean.TRUE : u.getActive())
                .applicantId(u.getApplicantId())
                .createdAt(u.getCreatedAt())
                .build();
    }

    // ── Activity timeline + top-actions rollup ──────────────────────────────

    private List<UserSupervisionResponse.ActivityRow> buildActivity(UUID userId) {
        // L3 round-2 — actor OR subject. The resolver bucket map drives the
        // subject-side OR clauses; null filters skip those clauses entirely.
        java.util.Map<String, java.util.Set<UUID>> buckets =
                userAuditEntityResolver.entityIdsForUser(userId);
        Page<AuditLog> page = auditLogRepository.findAll(
                AuditLogSpecifications.forUserAuditFeed(userId, buckets, null, null, null),
                PageRequest.of(0, ACTIVITY_LIMIT,
                        Sort.by(Sort.Direction.DESC, "timestamp")));
        List<UserSupervisionResponse.ActivityRow> out = new ArrayList<>(page.getContent().size());
        for (AuditLog a : page.getContent()) {
            out.add(UserSupervisionResponse.ActivityRow.builder()
                    .timestamp(a.getTimestamp())
                    .action(a.getAction())
                    .entityType(a.getEntityType())
                    .entityId(a.getEntityId())
                    .details(truncate(a.getAfterJson()))
                    .build());
        }
        return out;
    }

    private List<UserSupervisionResponse.ActionCount> buildTopActions(UUID userId) {
        java.util.Map<String, java.util.Set<UUID>> buckets =
                userAuditEntityResolver.entityIdsForUser(userId);
        Page<AuditLog> page = auditLogRepository.findAll(
                AuditLogSpecifications.forUserAuditFeed(userId, buckets, null, null, null),
                PageRequest.of(0, TOP_ACTIONS_SCAN_LIMIT,
                        Sort.by(Sort.Direction.DESC, "timestamp")));
        Map<String, Long> counts = new HashMap<>();
        for (AuditLog a : page.getContent()) {
            if (a.getAction() == null) continue;
            counts.merge(a.getAction(), 1L, Long::sum);
        }
        List<UserSupervisionResponse.ActionCount> rows = new ArrayList<>(counts.size());
        counts.forEach((k, v) -> rows.add(UserSupervisionResponse.ActionCount.builder()
                .action(k).count(v).build()));
        rows.sort(Comparator.comparingLong(UserSupervisionResponse.ActionCount::getCount)
                .reversed());
        return rows.size() > TOP_ACTIONS_LIMIT
                ? rows.subList(0, TOP_ACTIONS_LIMIT) : rows;
    }

    // ── Candidate context ───────────────────────────────────────────────────

    private UserSupervisionResponse.CandidateContext buildCandidateContext(User target) {
        Candidate candidate = candidateRepository.findByUserId(target.getId()).orElse(null);
        if (candidate == null) {
            // Role says APPLICANT/INTERN but no candidate row — return an empty
            // context block so the frontend can render "no candidate record".
            return UserSupervisionResponse.CandidateContext.builder()
                    .applications(Collections.emptyList())
                    .weeklyReports(Collections.emptyList())
                    .timesheets(Collections.emptyList())
                    .build();
        }
        UUID candidateId = candidate.getId();

        // Applications + posting + entity (graph-fetched).
        List<Application> apps = applicationRepository.findByCandidateIdWithPosting(candidateId);
        List<UserSupervisionResponse.ApplicationSummary> appSummaries = new ArrayList<>(apps.size());
        for (Application a : apps) {
            JobPosting jp = a.getJobPosting();
            appSummaries.add(UserSupervisionResponse.ApplicationSummary.builder()
                    .id(a.getId())
                    .position(jp != null ? jp.getTitle() : null)
                    .entityName(jp != null && jp.getEntity() != null
                            ? jp.getEntity().getName() : null)
                    .status(a.getStatus() != null ? a.getStatus().name() : null)
                    .appliedAt(a.getAppliedAt())
                    .href("/careers/erm/applications/" + a.getId())
                    .build());
        }

        // Engagement — pick the newest non-terminated one for the headline summary.
        List<Engagement> engagements = engagementRepository.findByCandidateId(candidateId);
        Engagement chosen = engagements.stream()
                .filter(e -> e.getStatus() != EngagementStatus.TERMINATED
                        && e.getStatus() != EngagementStatus.BLOCKED_NO_AUTHORIZATION)
                .max(Comparator.comparing(Engagement::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(engagements.stream()
                        .max(Comparator.comparing(Engagement::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .orElse(null));
        UserSupervisionResponse.EngagementSummary engagementSummary = null;
        if (chosen != null) {
            engagementSummary = UserSupervisionResponse.EngagementSummary.builder()
                    .id(chosen.getId())
                    .status(chosen.getStatus() != null ? chosen.getStatus().name() : null)
                    .plannedStartDate(chosen.getPlannedStartDate())
                    .actualStartDate(chosen.getActualStartDate())
                    .supervisorName(chosen.getSupervisor() != null
                            ? chosen.getSupervisor().getFullName() : null)
                    .entityName(chosen.getEntity() != null
                            ? chosen.getEntity().getName() : null)
                    .build();
        }

        // Compliance — STATUS + plain dates ONLY. No decrypted fields.
        UserSupervisionResponse.ComplianceStatus compliance = buildComplianceStatus(candidateId);

        // Weekly reports (newest first, capped).
        List<WeeklyReport> reports = weeklyReportRepository.findByInternIdWithGraph(candidateId);
        List<UserSupervisionResponse.ReportSummary> reportSummaries = new ArrayList<>(
                Math.min(reports.size(), CANDIDATE_REPORTS_LIMIT));
        for (WeeklyReport r : reports) {
            if (reportSummaries.size() >= CANDIDATE_REPORTS_LIMIT) break;
            reportSummaries.add(UserSupervisionResponse.ReportSummary.builder()
                    .id(r.getId())
                    .weekStart(r.getWeekStart())
                    .status(r.getStatus() != null ? r.getStatus().name() : null)
                    .submittedAt(r.getSubmittedAt())
                    .reviewedAt(r.getReviewedAt())
                    .build());
        }

        // Timesheets (newest first, capped).
        List<Timesheet> sheets = timesheetRepository.findForIntern(candidateId);
        List<UserSupervisionResponse.TimesheetSummary> timesheetSummaries = new ArrayList<>(
                Math.min(sheets.size(), CANDIDATE_TIMESHEETS_LIMIT));
        for (Timesheet t : sheets) {
            if (timesheetSummaries.size() >= CANDIDATE_TIMESHEETS_LIMIT) break;
            timesheetSummaries.add(UserSupervisionResponse.TimesheetSummary.builder()
                    .id(t.getId())
                    .weekStart(t.getWeekStart())
                    .status(t.getStatus() != null ? t.getStatus().name() : null)
                    .hours(t.getHours() != null ? t.getHours().toPlainString() : null)
                    .build());
        }

        // MaterialAcknowledgement count removed in Trainer Phase 0 — the
        // concept is not in the Trainer doc spec. The DTO field stays 0.
        long acks = 0L;

        return UserSupervisionResponse.CandidateContext.builder()
                .candidateId(candidateId)
                .applications(appSummaries)
                .engagement(engagementSummary)
                .compliance(compliance)
                .weeklyReports(reportSummaries)
                .timesheets(timesheetSummaries)
                .materialsAcknowledgedCount(acks)
                .build();
    }

    private UserSupervisionResponse.ComplianceStatus buildComplianceStatus(UUID candidateId) {
        UserSupervisionResponse.ComplianceStatus.ComplianceStatusBuilder b =
                UserSupervisionResponse.ComplianceStatus.builder();

        I9Form i9 = i9FormRepository.findByCandidateId(candidateId).orElse(null);
        if (i9 != null) {
            b.i9Status(i9.getStatus() != null ? i9.getStatus().name() : null)
                    .i9FirstDayOfEmployment(i9.getFirstDayOfEmployment())
                    .i9WorkAuthExpirationDate(i9.getWorkAuthExpirationDate())
                    .i9DetailHref("/careers/erm/i9-everify/i9/" + i9.getId());

            EVerifyCase ev = everifyCaseRepository.findByI9FormId(i9.getId()).orElse(null);
            if (ev != null) {
                b.everifyStatus(ev.getStatus() != null ? ev.getStatus().name() : null)
                        .everifyDetailHref("/careers/erm/i9-everify/everify/" + ev.getId());
            }
        }

        I983Plan latestPlan = i983PlanRepository
                .findByCandidateIdOrderByCreatedAtDesc(candidateId).stream()
                .findFirst().orElse(null);
        if (latestPlan != null) {
            b.i983Status(latestPlan.getStatus() != null ? latestPlan.getStatus().name() : null)
                    .i983OptEndDate(latestPlan.getOptEndDate())
                    .i983DetailHref("/careers/erm/training-plans/" + latestPlan.getId());
        }
        return b.build();
    }

    // ── Supervisor context ──────────────────────────────────────────────────

    private UserSupervisionResponse.SupervisorContext buildSupervisorContext(User target) {
        Set<EngagementStatus> activeOnly = EnumSet.of(EngagementStatus.ACTIVE);
        List<Engagement> active = engagementRepository
                .findBySupervisorIdAndStatusIn(target.getId(), activeOnly);

        long reportsAwaiting = 0L;
        long timesheetsAwaiting = 0L;
        List<UserSupervisionResponse.InternMini> roster = new ArrayList<>(
                Math.min(active.size(), SUPERVISOR_ROSTER_LIMIT));
        for (Engagement e : active) {
            if (roster.size() < SUPERVISOR_ROSTER_LIMIT
                    && e.getCandidate() != null && e.getCandidate().getId() != null) {
                UUID candidateId = e.getCandidate().getId();
                String name = e.getCandidate().getUser() != null
                        ? e.getCandidate().getUser().getFullName() : null;
                String position = e.getApplication() != null
                        && e.getApplication().getJobPosting() != null
                        ? e.getApplication().getJobPosting().getTitle() : null;
                roster.add(UserSupervisionResponse.InternMini.builder()
                        .candidateId(candidateId)
                        .name(name)
                        .position(position)
                        .engagementStatus(e.getStatus() != null ? e.getStatus().name() : null)
                        .reviewHref("/careers/trainer/weekly-reports?intern=" + candidateId)
                        .build());
            }

            // Backlog counts walk every active engagement (not just the rendered
            // first N) so the totals are accurate.
            if (e.getCandidate() == null || e.getCandidate().getId() == null) continue;
            UUID candidateId = e.getCandidate().getId();
            for (WeeklyReport r : weeklyReportRepository.findByInternIdWithGraph(candidateId)) {
                if (r.getStatus() == WeeklyReportStatus.SUBMITTED) {
                    reportsAwaiting++;
                }
            }
            for (Timesheet t : timesheetRepository.findForIntern(candidateId)) {
                if (t.getStatus() == TimesheetStatus.SUBMITTED) {
                    timesheetsAwaiting++;
                }
            }
        }

        long upcoming = evaluationSessionRepository.findForEvaluatorUser(target.getId()).stream()
                .filter(s -> s.getStatus() == EvaluationSessionStatus.SCHEDULED)
                .count();

        // WeeklyMaterial published-count removed in Trainer Phase 0 — the
        // concept is not in the Trainer doc spec. DTO field stays 0.
        long materialsPublished = 0L;

        return UserSupervisionResponse.SupervisorContext.builder()
                .assignedInterns(roster)
                .activeInternsCount(active.size())
                .reportsAwaitingReview(reportsAwaiting)
                .timesheetsAwaitingApproval(timesheetsAwaiting)
                .upcomingEvaluations(upcoming)
                .materialsPublished(materialsPublished)
                .build();
    }

    // ── Audit on view ───────────────────────────────────────────────────────

    private void writeViewAudit(User caller, User target) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("targetEmail", target.getEmail());
        if (target.getRoles() != null && !target.getRoles().isEmpty()) {
            after.put("targetRoles", String.join(",", target.getRoles().stream()
                    .map(Enum::name).toList()));
        }
        AuditLog entry = AuditLog.builder()
                .entityType("User")
                .entityId(target.getId())
                .action("SUPER_ADMIN_VIEWED_USER")
                .userId(caller != null ? caller.getId() : null)
                // L3 round-2 — populate the new subject column so future
                // queries don't need entity-resolution to find this row.
                .subjectUserId(target.getId())
                .afterJson(serializeJson(after))
                .build();
        try {
            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("Failed to write SUPER_ADMIN_VIEWED_USER audit (non-fatal): {}",
                    e.getMessage());
        }
    }

    /**
     * Public meta-audit helper used by the /audit endpoint so that
     * page-flips on the per-user log also write a forensic record. The
     * action name is caller-supplied (today: {@code SUPER_ADMIN_VIEWED_USER}).
     * Best-effort; never throws to the controller.
     */
    @Transactional
    public void writeAuditViewMeta(User caller, UUID targetUserId, String action) {
        if (targetUserId == null) return;
        User target = userRepository.findById(targetUserId).orElse(null);
        Map<String, Object> after = new LinkedHashMap<>();
        if (target != null) {
            after.put("targetEmail", target.getEmail());
            if (target.getRoles() != null && !target.getRoles().isEmpty()) {
                after.put("targetRoles", String.join(",", target.getRoles().stream()
                        .map(Enum::name).toList()));
            }
        }
        AuditLog entry = AuditLog.builder()
                .entityType("User")
                .entityId(targetUserId)
                .action(action != null ? action : "SUPER_ADMIN_VIEWED_USER")
                .userId(caller != null ? caller.getId() : null)
                .subjectUserId(targetUserId)
                .afterJson(serializeJson(after))
                .build();
        try {
            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("Failed to write {} meta-audit (non-fatal): {}",
                    entry.getAction(), e.getMessage());
        }
    }

    private String serializeJson(Map<String, Object> snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize supervision-view audit: {}", e.getMessage());
            return String.valueOf(snapshot);
        }
    }

    private static String truncate(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() <= 200) return s;
        return s.substring(0, 200) + "…";
    }
}
