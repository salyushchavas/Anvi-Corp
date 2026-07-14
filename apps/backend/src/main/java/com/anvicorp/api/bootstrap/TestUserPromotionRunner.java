package com.anvicorp.api.bootstrap;

import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.entity.StaffingEntity;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ApplicationStatus;
import com.anvicorp.api.enums.CompensationFrequency;
import com.anvicorp.api.enums.EmploymentType;
import com.anvicorp.api.enums.EngagementStatus;
import com.anvicorp.api.enums.JobPostingStatus;
import com.anvicorp.api.enums.OfferStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.EngagementRepository;
import com.anvicorp.api.repository.JobPostingRepository;
import com.anvicorp.api.repository.OfferRepository;
import com.anvicorp.api.repository.StaffingEntityRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.service.EngagementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * TEMP: Remove after the activation-gate fix and role-based-access refactor
 * are deployed and verified end-to-end.
 *
 * <p>One-shot promotion runner for a single hard-coded test user. When
 * enabled, finds the user by email, forces role=INTERN +
 * emailVerified=true + active=true, ensures an Engagement exists (creating
 * the StaffingEntity / JobPosting / Application / Offer chain if needed),
 * and walks the engagement PENDING_COMPLIANCE → READY_TO_START → ACTIVE
 * via {@link EngagementService#transitionToSystem} with audit action
 * {@code TEST_BOOTSTRAP_PROMOTION}.</p>
 *
 * <p>Bypasses the activation gate, the compliance routing service, and the
 * per-engagement supervisor / RM assignment requirement. Idempotent: if the
 * user is already INTERN with an ACTIVE engagement, logs and skips.</p>
 *
 * <p>Gated by {@code app.bootstrap.promote-test-user-enabled=true}
 * (default false). Operator flips it on, redeploys once to promote,
 * flips it back off.</p>
 */
@Component
@Order(25)
@Slf4j
public class TestUserPromotionRunner implements ApplicationRunner {

    private static final String LOG_TAG = "[TestUserPromotionRunner]";
    private static final String TARGET_EMAIL = "abhizoe5+test5@gmail.com";

    private static final String TEST_ENTITY_NAME = "Test Entity (bootstrap)";
    private static final String TEST_POSTING_SLUG = "test-promotion-bootstrap-posting";
    private static final String AUDIT_ACTION = "TEST_BOOTSTRAP_PROMOTION";

    private final boolean enabled;
    private final UserRepository userRepository;
    private final CandidateRepository candidateRepository;
    private final StaffingEntityRepository staffingEntityRepository;
    private final JobPostingRepository jobPostingRepository;
    private final ApplicationRepository applicationRepository;
    private final OfferRepository offerRepository;
    private final EngagementRepository engagementRepository;
    private final EngagementService engagementService;

    public TestUserPromotionRunner(
            @Value("${app.bootstrap.promote-test-user-enabled:false}") boolean enabled,
            UserRepository userRepository,
            CandidateRepository candidateRepository,
            StaffingEntityRepository staffingEntityRepository,
            JobPostingRepository jobPostingRepository,
            ApplicationRepository applicationRepository,
            OfferRepository offerRepository,
            EngagementRepository engagementRepository,
            EngagementService engagementService) {
        this.enabled = enabled;
        this.userRepository = userRepository;
        this.candidateRepository = candidateRepository;
        this.staffingEntityRepository = staffingEntityRepository;
        this.jobPostingRepository = jobPostingRepository;
        this.applicationRepository = applicationRepository;
        this.offerRepository = offerRepository;
        this.engagementRepository = engagementRepository;
        this.engagementService = engagementService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("{} skipped — disabled "
                    + "(set app.bootstrap.promote-test-user-enabled=true to enable).",
                    LOG_TAG);
            return;
        }

        int scanned = 1;
        int promoted = 0;
        int skipped = 0;
        int failed = 0;

        try {
            User user = userRepository.findByEmail(TARGET_EMAIL).orElse(null);
            if (user == null) {
                log.warn("{} target user not found — skipping (email={})",
                        LOG_TAG, TARGET_EMAIL);
                skipped = 1;
                logSummary(scanned, promoted, skipped, failed);
                return;
            }

            // 1. Force user fields into the right shape.
            boolean userDirty = false;
            if (user.getRoles() == null) {
                user.setRoles(EnumSet.of(UserRole.INTERN));
                userDirty = true;
            } else if (!user.getRoles().contains(UserRole.INTERN)) {
                user.getRoles().add(UserRole.INTERN);
                userDirty = true;
            }
            if (!Boolean.TRUE.equals(user.getEmailVerified())) {
                user.setEmailVerified(true);
                userDirty = true;
            }
            if (!Boolean.TRUE.equals(user.getActive())) {
                user.setActive(true);
                userDirty = true;
            }
            if (userDirty) {
                user = userRepository.save(user);
                log.info("{} user {} (id={}) → role=INTERN, emailVerified=true, active=true",
                        LOG_TAG, TARGET_EMAIL, user.getId());
            } else {
                log.info("{} user {} (id={}) → already in target shape",
                        LOG_TAG, TARGET_EMAIL, user.getId());
            }

            // 2. Find or create Candidate row (1:1 with User). `user` is
            // (re)assigned above; capture an effectively-final reference for
            // the lambda.
            final User savedUser = user;
            Candidate candidate = candidateRepository.findByUserId(savedUser.getId())
                    .orElseGet(() -> {
                        Candidate c = Candidate.builder().user(savedUser).build();
                        c = candidateRepository.save(c);
                        log.info("{} candidate row for {} → CREATED (id={})",
                                LOG_TAG, TARGET_EMAIL, c.getId());
                        return c;
                    });

            // 3. Find latest engagement OR build the upstream chain.
            Engagement engagement = engagementRepository
                    .findByCandidateId(candidate.getId()).stream()
                    .max(Comparator.comparing(
                            Engagement::getCreatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElse(null);

            if (engagement == null) {
                engagement = buildEngagementChain(user, candidate);
                log.info("{} engagement created for {} (id={})",
                        LOG_TAG, TARGET_EMAIL, engagement.getId());
            } else {
                log.info("{} engagement reused for {} (id={}, status={})",
                        LOG_TAG, TARGET_EMAIL, engagement.getId(), engagement.getStatus());
            }

            // 4. Walk engagement status to ACTIVE, idempotent at each step.
            EngagementStatus current = engagement.getStatus();
            if (current == EngagementStatus.ACTIVE) {
                log.info("{} engagement already ACTIVE — no transition needed", LOG_TAG);
                skipped = 1;
            } else if (current == EngagementStatus.TERMINATED
                    || current == EngagementStatus.BLOCKED_NO_AUTHORIZATION
                    || current == EngagementStatus.COMPLETED) {
                log.warn("{} engagement is in terminal state {} — cannot promote to ACTIVE",
                        LOG_TAG, current);
                failed = 1;
            } else {
                if (current == EngagementStatus.PENDING_COMPLIANCE) {
                    engagement = engagementService.transitionToSystem(
                            engagement,
                            EngagementStatus.READY_TO_START,
                            AUDIT_ACTION,
                            null);
                    log.info("{} engagement {} → READY_TO_START",
                            LOG_TAG, engagement.getId());
                }
                if (engagement.getStatus() == EngagementStatus.READY_TO_START) {
                    engagement = engagementService.transitionToSystem(
                            engagement,
                            EngagementStatus.ACTIVE,
                            AUDIT_ACTION,
                            null);
                    log.info("{} engagement {} → ACTIVE",
                            LOG_TAG, engagement.getId());
                }
                promoted = 1;
            }
        } catch (Exception e) {
            failed = 1;
            log.warn("{} promotion failed (non-fatal): {}", LOG_TAG, e.getMessage(), e);
        }

        logSummary(scanned, promoted, skipped, failed);
    }

    private void logSummary(int scanned, int promoted, int skipped, int failed) {
        log.info("{} summary: scanned={}, promoted={}, skipped={}, failed={}",
                LOG_TAG, scanned, promoted, skipped, failed);
    }

    // ── Upstream chain — mirrors TestAccountSeeder's pattern ────────────────

    private Engagement buildEngagementChain(User user, Candidate candidate) {
        StaffingEntity entity = findOrCreateEntity();
        JobPosting posting = findOrCreatePosting(entity);
        Application application = findOrCreateApplication(candidate, posting);
        Offer offer = findOrCreateOffer(application, user);

        Engagement engagement = Engagement.builder()
                .application(application)
                .candidate(candidate)
                .offer(offer)
                .entity(entity)
                // No supervisor / reportingManager FK — role-based access
                // makes per-engagement assignment optional.
                .status(EngagementStatus.PENDING_COMPLIANCE)
                .plannedStartDate(LocalDate.now())
                .createdBy(user.getId())
                .build();
        return engagementRepository.save(engagement);
    }

    private StaffingEntity findOrCreateEntity() {
        return staffingEntityRepository.findAll().stream()
                .filter(e -> TEST_ENTITY_NAME.equals(e.getName()))
                .findFirst()
                .orElseGet(() -> {
                    StaffingEntity e = StaffingEntity.builder()
                            .name(TEST_ENTITY_NAME)
                            .isActive(true)
                            .build();
                    e = staffingEntityRepository.save(e);
                    log.info("{} staffing entity \"{}\" → CREATED (id={})",
                            LOG_TAG, TEST_ENTITY_NAME, e.getId());
                    return e;
                });
    }

    private JobPosting findOrCreatePosting(StaffingEntity entity) {
        return jobPostingRepository.findBySlug(TEST_POSTING_SLUG)
                .orElseGet(() -> {
                    JobPosting p = JobPosting.builder()
                            .entity(entity)
                            .slug(TEST_POSTING_SLUG)
                            .title("Test promotion bootstrap posting")
                            .description("Auto-created by TestUserPromotionRunner — not a real posting.")
                            .employmentType(EmploymentType.INTERNSHIP)
                            .status(JobPostingStatus.CLOSED)
                            .build();
                    p = jobPostingRepository.save(p);
                    log.info("{} job posting \"{}\" → CREATED (id={})",
                            LOG_TAG, TEST_POSTING_SLUG, p.getId());
                    return p;
                });
    }

    private Application findOrCreateApplication(Candidate candidate, JobPosting posting) {
        List<Application> existing = applicationRepository.findByCandidateId(candidate.getId());
        Application match = existing.stream()
                .filter(a -> a.getJobPosting() != null
                        && posting.getId().equals(a.getJobPosting().getId()))
                .findFirst()
                .orElse(null);
        if (match != null) return match;
        Application a = Application.builder()
                .candidate(candidate)
                .jobPosting(posting)
                .status(ApplicationStatus.ACCEPTED)
                .build();
        a = applicationRepository.save(a);
        log.info("{} application (test bootstrap) → CREATED (id={})", LOG_TAG, a.getId());
        return a;
    }

    private Offer findOrCreateOffer(Application application, User createdBy) {
        return offerRepository.findByApplicationIdOrderByCreatedAtDesc(application.getId())
                .stream().findFirst()
                .orElseGet(() -> {
                    Instant now = Instant.now();
                    Offer o = Offer.builder()
                            .application(application)
                            .compensationAmount(new BigDecimal("25.00"))
                            .compensationFrequency(CompensationFrequency.HOURLY)
                            .compensationCurrency("USD")
                            .startDate(LocalDate.now())
                            .expiresAt(now.plus(180, ChronoUnit.DAYS))
                            .status(OfferStatus.ACCEPTED)
                            .letterContent(
                                    "Auto-created by TestUserPromotionRunner — not a real offer.")
                            .createdBy(createdBy.getId())
                            .respondedAt(now)
                            .build();
                    o = offerRepository.save(o);
                    log.info("{} offer (test bootstrap, ACCEPTED) → CREATED (id={})",
                            LOG_TAG, o.getId());
                    return o;
                });
    }

    @SuppressWarnings("unused")
    private static UUID unused() { return null; }
}
