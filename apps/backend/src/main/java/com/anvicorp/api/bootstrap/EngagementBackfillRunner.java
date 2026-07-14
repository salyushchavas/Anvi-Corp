package com.anvicorp.api.bootstrap;

import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.EvaluationSession;
import com.anvicorp.api.entity.I9Form;
import com.anvicorp.api.entity.I983Plan;
import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.entity.OnboardingTask;
import com.anvicorp.api.entity.Timesheet;
import com.anvicorp.api.entity.WorkAssignment;
import com.anvicorp.api.enums.ApplicationStatus;
import com.anvicorp.api.enums.EngagementStatus;
import com.anvicorp.api.enums.OfferStatus;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.EngagementRepository;
import com.anvicorp.api.repository.EvaluationSessionRepository;
import com.anvicorp.api.repository.I9FormRepository;
import com.anvicorp.api.repository.I983PlanRepository;
import com.anvicorp.api.repository.OfferRepository;
import com.anvicorp.api.repository.OnboardingTaskRepository;
import com.anvicorp.api.repository.TimesheetRepository;
import com.anvicorp.api.repository.WorkAssignmentRepository;
import com.anvicorp.api.service.EngagementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Phase 3 step 11 — opt-in backfill runner. New offer acceptances (post-step-3)
 * create engagements automatically. This runner handles the legacy gap: existing
 * post-offer applications that pre-date Phase 3 get an Engagement row created
 * for them, and their I-9 / I-983 / onboarding / Group C records get linked.
 *
 * <b>Opt-in.</b> Controlled by {@code app.engagement.backfill-enabled} (default
 * FALSE per locked decision #5). Historical internships only get engagements
 * if the operator explicitly enables it.
 *
 * <b>Idempotent.</b> Applications that already have an engagement are skipped.
 * Compliance + Group C rows are only linked when their current engagement_id
 * is null — re-runs leave already-linked rows alone.
 *
 * <b>Catch-and-log.</b> Each application is wrapped independently; one bad
 * row can't crash the boot. The track router is NOT invoked — backfilled
 * engagements are historical, not live, so we skip auto-task creation +
 * status routing.
 *
 * <b>Ordering.</b> {@code @Order(25)} — runs LAST among bootstrap runners,
 * after {@code SchemaFixupRunner} (HIGHEST_PRECEDENCE), {@code AdminSeeder} (1),
 * {@code SeedDemoDataRunner} (4), {@code HiredInternBackfillRunner} (5), and
 * {@code VerificationBackfillRunner} (20). That way the demo seeders have
 * already produced their applications by the time we look.
 */
@Component
@Order(25)
@Slf4j
public class EngagementBackfillRunner implements CommandLineRunner {

    @SuppressWarnings("deprecation") // intentionally listing the deprecated post-offer statuses
    private static final Set<ApplicationStatus> POST_OFFER_STATUSES = Set.of(
            ApplicationStatus.ACCEPTED,
            ApplicationStatus.ONBOARDING,
            ApplicationStatus.ACTIVE,
            ApplicationStatus.HIRED,
            ApplicationStatus.COMPLETED
    );

    private final boolean enabled;
    private final ApplicationRepository applicationRepository;
    private final EngagementRepository engagementRepository;
    private final OfferRepository offerRepository;
    private final EngagementService engagementService;
    private final I9FormRepository i9FormRepository;
    private final I983PlanRepository i983PlanRepository;
    private final OnboardingTaskRepository onboardingTaskRepository;
    private final WorkAssignmentRepository workAssignmentRepository;
    private final TimesheetRepository timesheetRepository;
    private final EvaluationSessionRepository evaluationSessionRepository;

    public EngagementBackfillRunner(
            @Value("${app.engagement.backfill-enabled:false}") boolean enabled,
            ApplicationRepository applicationRepository,
            EngagementRepository engagementRepository,
            OfferRepository offerRepository,
            EngagementService engagementService,
            I9FormRepository i9FormRepository,
            I983PlanRepository i983PlanRepository,
            OnboardingTaskRepository onboardingTaskRepository,
            WorkAssignmentRepository workAssignmentRepository,
            TimesheetRepository timesheetRepository,
            EvaluationSessionRepository evaluationSessionRepository) {
        this.enabled = enabled;
        this.applicationRepository = applicationRepository;
        this.engagementRepository = engagementRepository;
        this.offerRepository = offerRepository;
        this.engagementService = engagementService;
        this.i9FormRepository = i9FormRepository;
        this.i983PlanRepository = i983PlanRepository;
        this.onboardingTaskRepository = onboardingTaskRepository;
        this.workAssignmentRepository = workAssignmentRepository;
        this.timesheetRepository = timesheetRepository;
        this.evaluationSessionRepository = evaluationSessionRepository;
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            log.info("Engagement backfill skipped — disabled (set app.engagement.backfill-enabled=true to enable).");
            return;
        }

        List<Application> candidates;
        try {
            candidates = applicationRepository.findByStatusIn(POST_OFFER_STATUSES);
        } catch (Exception e) {
            log.warn("Engagement backfill — failed to load post-offer applications (non-fatal): {}",
                    e.getMessage(), e);
            return;
        }

        int created = 0;
        int skippedAlreadyLinked = 0;
        int skippedNoOffer = 0;
        int failed = 0;
        int totalLinks = 0;
        for (Application app : candidates) {
            try {
                // Idempotency: skip apps that already have an engagement.
                if (engagementRepository.existsByApplicationId(app.getId())) {
                    skippedAlreadyLinked++;
                    continue;
                }
                Offer offer = pickAcceptedOffer(app);
                if (offer == null) {
                    log.warn("Engagement backfill — application {} ({}): no accepted offer; skipping",
                            app.getId(), app.getStatus());
                    skippedNoOffer++;
                    continue;
                }

                // Phase 3 step 3's createForAcceptedOffer is idempotent +
                // REQUIRES_NEW; it handles the audit row and unique-constraint
                // protection for us. SYSTEM actor (null) — these are historical.
                Engagement engagement = engagementService.createForAcceptedOffer(offer, null);
                if (engagement == null) {
                    log.warn("Engagement backfill — application {}: create returned null; skipping",
                            app.getId());
                    failed++;
                    continue;
                }

                // Override the status from the legacy Application post-offer
                // state. The transition is via the system path (no
                // LEGAL_TRANSITIONS check, still audited).
                EngagementStatus target = mapStatus(app.getStatus());
                if (target != EngagementStatus.PENDING_COMPLIANCE) {
                    // Pre-set historical dates so transitionToSystem doesn't
                    // overwrite them with today's date.
                    if (target == EngagementStatus.ACTIVE
                            && engagement.getActualStartDate() == null
                            && engagement.getPlannedStartDate() != null) {
                        engagement.setActualStartDate(engagement.getPlannedStartDate());
                    }
                    if (target == EngagementStatus.COMPLETED) {
                        if (engagement.getActualStartDate() == null
                                && engagement.getPlannedStartDate() != null) {
                            engagement.setActualStartDate(engagement.getPlannedStartDate());
                        }
                        if (engagement.getActualEndDate() == null
                                && engagement.getPlannedEndDate() != null) {
                            engagement.setActualEndDate(engagement.getPlannedEndDate());
                        }
                    }
                    engagementService.transitionToSystem(engagement, target,
                            "BACKFILL_LEGACY", null);
                }
                created++;

                // Link this candidate's existing compliance + Group C rows
                // (those still missing engagement_id) to the new engagement.
                totalLinks += linkCandidateRows(engagement);
            } catch (Exception e) {
                failed++;
                log.warn("Engagement backfill — application {} failed (non-fatal): {}",
                        app.getId(), e.getMessage(), e);
            }
        }
        log.warn(
                "Engagement backfill complete — created={}, skippedAlreadyLinked={}, "
                        + "skippedNoOffer={}, failed={}, totalLinks={}",
                created, skippedAlreadyLinked, skippedNoOffer, failed, totalLinks);
    }

    /**
     * Pick the candidate's "the deal landed" Offer for this application. Most
     * recent by createdAt — historical applications may have had multiple
     * offers and we pick the one that completed.
     *
     * <p>Accepts both {@link OfferStatus#ACCEPTED} (the legacy click-to-accept
     * flow via {@code OfferService.acceptInternal}) AND {@link
     * OfferStatus#SIGNED} (the in-house IDMS sign flow via {@code
     * OfferIdmsSigningService.finalizeIdmsSigning}). Same precedent as
     * {@code InternActivationJob.tryActivateIfReady} which treats both as
     * eligible. Restricting to ACCEPTED only would silently skip every
     * IDMS-signed orphan — which was the original gap this backfill exists
     * to repair.</p>
     */
    @SuppressWarnings("deprecation") // OfferStatus.ACCEPTED is the legacy pre-IDMS value;
                                     // historical rows still carry it and must be backfilled.
    private Offer pickAcceptedOffer(Application app) {
        return offerRepository
                .findByApplicationIdOrderByCreatedAtDesc(app.getId()).stream()
                .filter(o -> o.getStatus() == OfferStatus.ACCEPTED
                        || o.getStatus() == OfferStatus.SIGNED)
                .max(Comparator.comparing(Offer::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
    }

    @SuppressWarnings("deprecation") // mapping intentionally references deprecated values
    private EngagementStatus mapStatus(ApplicationStatus s) {
        if (s == null) return EngagementStatus.PENDING_COMPLIANCE;
        return switch (s) {
            case ACCEPTED, ONBOARDING -> EngagementStatus.PENDING_COMPLIANCE;
            case HIRED, ACTIVE -> EngagementStatus.ACTIVE;
            case COMPLETED -> EngagementStatus.COMPLETED;
            default -> EngagementStatus.PENDING_COMPLIANCE;
        };
    }

    /**
     * Update I-9, I-983, onboarding tasks, work assignments, timesheets and
     * evaluation sessions for this candidate that have a null engagement FK.
     * Returns the number of rows touched across all five entities. Each block
     * is wrapped so a single broken row doesn't kill the whole link pass.
     */
    private int linkCandidateRows(Engagement engagement) {
        Candidate candidate = engagement.getCandidate();
        if (candidate == null) return 0;
        java.util.UUID candidateId = candidate.getId();
        int touched = 0;

        try {
            I9Form i9 = i9FormRepository.findByCandidateId(candidateId).orElse(null);
            if (i9 != null && i9.getEngagement() == null) {
                i9.setEngagement(engagement);
                i9FormRepository.save(i9);
                touched++;
            }
        } catch (Exception e) {
            log.warn("Link I-9 failed for candidate {}: {}", candidateId, e.getMessage());
        }

        try {
            List<I983Plan> plans = i983PlanRepository
                    .findByCandidateIdOrderByCreatedAtDesc(candidateId);
            for (I983Plan p : plans) {
                if (p.getEngagement() == null) {
                    p.setEngagement(engagement);
                    i983PlanRepository.save(p);
                    touched++;
                }
            }
        } catch (Exception e) {
            log.warn("Link I-983 failed for candidate {}: {}", candidateId, e.getMessage());
        }

        try {
            List<OnboardingTask> tasks = onboardingTaskRepository
                    .findByCandidateIdOrderBySortOrderAsc(candidateId);
            for (OnboardingTask t : tasks) {
                if (t.getEngagement() == null) {
                    t.setEngagement(engagement);
                    onboardingTaskRepository.save(t);
                    touched++;
                }
            }
        } catch (Exception e) {
            log.warn("Link onboarding tasks failed for candidate {}: {}", candidateId, e.getMessage());
        }

        try {
            List<WorkAssignment> wa = workAssignmentRepository.findForIntern(candidateId);
            for (WorkAssignment a : wa) {
                if (a.getEngagement() == null) {
                    a.setEngagement(engagement);
                    workAssignmentRepository.save(a);
                    touched++;
                }
            }
        } catch (Exception e) {
            log.warn("Link work assignments failed for candidate {}: {}", candidateId, e.getMessage());
        }

        try {
            List<Timesheet> ts = timesheetRepository.findForIntern(candidateId);
            for (Timesheet t : ts) {
                if (t.getEngagement() == null) {
                    t.setEngagement(engagement);
                    timesheetRepository.save(t);
                    touched++;
                }
            }
        } catch (Exception e) {
            log.warn("Link timesheets failed for candidate {}: {}", candidateId, e.getMessage());
        }

        try {
            List<EvaluationSession> sessions = evaluationSessionRepository.findForIntern(candidateId);
            for (EvaluationSession s : sessions) {
                if (s.getEngagement() == null) {
                    s.setEngagement(engagement);
                    evaluationSessionRepository.save(s);
                    touched++;
                }
            }
        } catch (Exception e) {
            log.warn("Link evaluation sessions failed for candidate {}: {}", candidateId, e.getMessage());
        }

        return touched;
    }
}
