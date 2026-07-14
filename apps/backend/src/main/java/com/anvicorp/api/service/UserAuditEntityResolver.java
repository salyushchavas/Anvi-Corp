package com.anvicorp.api.service;

import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.EvaluationSession;
import com.anvicorp.api.entity.I983Plan;
import com.anvicorp.api.entity.I9Form;
import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.entity.OnboardingTask;
import com.anvicorp.api.entity.Timesheet;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.Interview;
import com.anvicorp.api.entity.WeeklyReport;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.EVerifyCaseRepository;
import com.anvicorp.api.repository.EngagementRepository;
import com.anvicorp.api.repository.EvaluationSessionRepository;
import com.anvicorp.api.repository.I9FormRepository;
import com.anvicorp.api.repository.I983PlanRepository;
import com.anvicorp.api.repository.InterviewRepository;
import com.anvicorp.api.repository.OfferRepository;
import com.anvicorp.api.repository.OnboardingTaskRepository;
import com.anvicorp.api.repository.ScreeningRepository;
import com.anvicorp.api.repository.TimesheetRepository;
import com.anvicorp.api.repository.WeeklyReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolves the set of entity ids a user is the SUBJECT of — used by the
 * per-user audit feed to find rows where the user wasn't the actor but was
 * the affected party (e.g. HR submitted Section 2 on THIS candidate's I-9).
 *
 * <h2>Why a resolver, not a column</h2>
 * The audit table has a new {@code subject_user_id} column that new writers
 * can populate. But 16 existing audit-write sites don't yet populate it, and
 * mass-refactoring all of them is out of scope here. This resolver lets the
 * read path cover BOTH historical rows and any future code paths that
 * forget to populate the column — by walking the candidate's owned entities
 * (apps / engagements / I-9 / I-983 / e-verify / offers / reports /
 * timesheets / acks / interviews / onboarding tasks / evaluations / screenings)
 * and feeding their ids into the audit-feed Specification.
 *
 * <h2>Coverage limits (honest)</h2>
 * <ul>
 *   <li>Resolution requires a Candidate row — staff-only users (OPERATIONS /
 *       HR / TECHNICAL_EVALUATOR / EXECUTIVE / SUPER_ADMIN)
 *       return an empty map; the audit feed for them is actor-side +
 *       User-entity-targeted rows only. That's the right answer — staff
 *       don't have personal cycle data to surface.</li>
 *   <li>WorkAssignment isn't covered (no intern-keyed query exists today;
 *       adding one is a follow-up). Audit rows on WorkAssignment will
 *       still surface via actor-side and via {@code subject_user_id} for
 *       new writes once those paths populate it.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class UserAuditEntityResolver {

    private final CandidateRepository candidateRepository;
    private final ApplicationRepository applicationRepository;
    private final EngagementRepository engagementRepository;
    private final I9FormRepository i9FormRepository;
    private final I983PlanRepository i983PlanRepository;
    private final EVerifyCaseRepository everifyCaseRepository;
    private final OfferRepository offerRepository;
    private final WeeklyReportRepository weeklyReportRepository;
    private final TimesheetRepository timesheetRepository;
    // MaterialAcknowledgementRepository removed in Trainer Phase 0.
    private final InterviewRepository interviewRepository;
    private final OnboardingTaskRepository onboardingTaskRepository;
    private final EvaluationSessionRepository evaluationSessionRepository;
    private final ScreeningRepository screeningRepository;

    /**
     * Build the subject-side entity-id buckets for a user. Returns an empty
     * map for staff-only users (no Candidate row). Each bucket key matches
     * {@link com.anvicorp.api.entity.AuditLog#getEntityType()} so the
     * Specification can do a single AND per bucket.
     */
    @Transactional(readOnly = true)
    public Map<String, Set<UUID>> entityIdsForUser(UUID userId) {
        Map<String, Set<UUID>> buckets = new HashMap<>();
        if (userId == null) return buckets;

        Optional<Candidate> candidateOpt = candidateRepository.findByUserId(userId);
        if (candidateOpt.isEmpty()) {
            // Staff user — no candidate-keyed data. The caller adds the
            // User-entity bucket separately.
            return buckets;
        }
        UUID candidateId = candidateOpt.get().getId();

        // Applications
        List<Application> apps = applicationRepository.findByCandidateId(candidateId);
        Set<UUID> appIds = apps.stream()
                .map(Application::getId).collect(Collectors.toSet());
        if (!appIds.isEmpty()) buckets.put("Application", appIds);

        // Engagements
        List<Engagement> engagements = engagementRepository.findByCandidateId(candidateId);
        Set<UUID> engagementIds = engagements.stream()
                .map(Engagement::getId).collect(Collectors.toSet());
        if (!engagementIds.isEmpty()) buckets.put("Engagement", engagementIds);

        // I-9 (one-per-candidate)
        Optional<I9Form> i9Opt = i9FormRepository.findByCandidateId(candidateId);
        i9Opt.ifPresent(i9 -> {
            buckets.put("I9Form", Set.of(i9.getId()));
            // E-Verify case (one-per-I9 by FK)
            everifyCaseRepository.findByI9FormId(i9.getId())
                    .ifPresent(ev -> buckets.put("EVerifyCase", Set.of(ev.getId())));
        });

        // I-983 plans
        Set<UUID> planIds = i983PlanRepository.findByCandidateIdOrderByCreatedAtDesc(candidateId)
                .stream().map(I983Plan::getId).collect(Collectors.toSet());
        if (!planIds.isEmpty()) buckets.put("I983Plan", planIds);

        // Offers (per candidate via application)
        Set<UUID> offerIds = offerRepository
                .findByApplication_Candidate_IdOrderByCreatedAtDesc(candidateId).stream()
                .map(Offer::getId).collect(Collectors.toSet());
        if (!offerIds.isEmpty()) buckets.put("Offer", offerIds);

        // Weekly reports
        Set<UUID> reportIds = weeklyReportRepository.findByInternIdWithGraph(candidateId).stream()
                .map(WeeklyReport::getId).collect(Collectors.toSet());
        if (!reportIds.isEmpty()) buckets.put("WeeklyReport", reportIds);

        // Timesheets
        Set<UUID> timesheetIds = timesheetRepository.findForIntern(candidateId).stream()
                .map(Timesheet::getId).collect(Collectors.toSet());
        if (!timesheetIds.isEmpty()) buckets.put("Timesheet", timesheetIds);

        // MaterialAcknowledgement bucket removed in Trainer Phase 0 — the
        // concept is not in the Trainer doc spec.

        // Interviews (via applications)
        Set<UUID> interviewIds = new HashSet<>();
        for (UUID appId : appIds) {
            for (Interview iv : interviewRepository
                    .findByApplicationIdOrderByScheduledAtDesc(appId)) {
                interviewIds.add(iv.getId());
            }
        }
        if (!interviewIds.isEmpty()) buckets.put("Interview", interviewIds);

        // Onboarding tasks
        Set<UUID> taskIds = onboardingTaskRepository
                .findByCandidateIdOrderBySortOrderAsc(candidateId).stream()
                .map(OnboardingTask::getId).collect(Collectors.toSet());
        if (!taskIds.isEmpty()) buckets.put("OnboardingTask", taskIds);

        // Evaluation sessions
        Set<UUID> sessionIds = evaluationSessionRepository.findForIntern(candidateId).stream()
                .map(EvaluationSession::getId).collect(Collectors.toSet());
        if (!sessionIds.isEmpty()) buckets.put("EvaluationSession", sessionIds);

        // Screenings (one-per-application by FK)
        Set<UUID> screeningIds = new HashSet<>();
        for (UUID appId : appIds) {
            screeningRepository.findByApplicationIdWithGraph(appId)
                    .ifPresent(s -> screeningIds.add(s.getId()));
        }
        if (!screeningIds.isEmpty()) buckets.put("Screening", screeningIds);

        return buckets;
    }
}
