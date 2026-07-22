package com.anvicorp.api.bootstrap;

import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.Interview;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.entity.StaffingEntity;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.entity.WorkAuthorizationRecord;
import com.anvicorp.api.enums.ApplicationStatus;
import com.anvicorp.api.enums.CompensationFrequency;
import com.anvicorp.api.enums.EmploymentType;
import com.anvicorp.api.enums.InternLifecycleStatus;
import com.anvicorp.api.enums.InterviewRecommendation;
import com.anvicorp.api.enums.InterviewStatus;
import com.anvicorp.api.enums.InterviewType;
import com.anvicorp.api.enums.JobPostingStatus;
import com.anvicorp.api.enums.OfferStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.enums.WorkAuthTrack;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.InterviewRepository;
import com.anvicorp.api.repository.JobPostingRepository;
import com.anvicorp.api.repository.OfferRepository;
import com.anvicorp.api.repository.StaffingEntityRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.repository.WorkAuthorizationRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 8.3 — one-shot, idempotent dev/staging seeder that establishes a
 * complete environment ending at the Pending Document Assignment state:
 * 4 supporting users (ERM, Trainer, Evaluator, Manager), 1 OPEN job
 * posting, and 3 test interns whose pipeline runs from
 * applicant → application → interview SELECTED → offer SIGNED → employee
 * ID minted → reporting structure assigned. The next ERM action — for
 * each of the 3 interns — is to assign the document packet.
 *
 * <h2>Gating</h2>
 * Activated by {@code SEED_TEST_DATA=true} env var (or
 * {@code app.seed.test-data-enabled=true} Spring property). Default is
 * false; the body returns silently. NEVER set true in production.
 *
 * <h2>Idempotency</h2>
 * Every row is keyed by a stable identifier (user email, job posting
 * slug, application by candidate+posting, offer by legacy envelope_id).
 * Re-runs skip cleanly with INFO logs. Per-intern try-catch so a partial
 * failure on one intern doesn't block the others.
 *
 * <h2>Staggered timestamps</h2>
 * Application applied 30d ago, interviewed 21d ago, offer sent 14d ago.
 * Signed dates are staggered per intern (3 / 8 / 14 days ago) so the
 * "Awaiting document packet" urgent KPI (signed > 7d) lights up for
 * Priya and Kavya but not Aarav.
 */
@Component
@Order(50)
@Slf4j
public class TestDataSeeder implements CommandLineRunner {

    private static final String LOG_TAG = "[TestDataSeeder]";
    /** Password for the seeded TRAINER / EVALUATOR / MANAGER staff users. */
    private static final String STAFF_PASSWORD = "Test1234!";
    /** Phase 8.3.3 — fresh password for the 3 demo intern accounts. */
    private static final String INTERN_PASSWORD = "Demo2026!";

    /** Phase 8.3.3 — the real ERM account the developer logs in as. The
     *  seeder does NOT create this user; it looks it up. If it's missing,
     *  the seeder falls back to any user with the ERM role and logs a
     *  warning. The earlier test-erm@anvicorp.test scope mismatch was the
     *  original Pending-tab-stays-empty bug, so don't reintroduce it. */
    private static final String REAL_ERM_EMAIL = "erm@anvicorp.test";

    private static final String TRAINER_EMAIL = "test-trainer@anvicorp.test";
    private static final String EVALUATOR_EMAIL = "test-evaluator@anvicorp.test";
    private static final String MANAGER_EMAIL = "test-manager@anvicorp.test";

    private static final String TEST_ENTITY_NAME = "Anvi Corp Test Entity (Phase 8.3)";
    private static final String TEST_POSTING_SLUG = "skyzen-test-java-fullstack-intern";

    private final boolean enabled;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CandidateRepository candidateRepository;
    private final StaffingEntityRepository staffingEntityRepository;
    private final JobPostingRepository jobPostingRepository;
    private final ApplicationRepository applicationRepository;
    private final InterviewRepository interviewRepository;
    private final OfferRepository offerRepository;
    private final InternLifecycleRepository lifecycleRepository;
    private final WorkAuthorizationRecordRepository workAuthRepository;
    private final JdbcTemplate jdbc;
    /** Phase 8.3.3 — explicit transactional wrapper for each intern chain.
     *  Spring's @Transactional only applies through a proxy boundary,
     *  which is bypassed when CommandLineRunner.run() self-invokes
     *  seedIntern, so we use TransactionTemplate to get the same
     *  rollback semantics without the proxy gotcha. */
    private final TransactionTemplate txn;

    public TestDataSeeder(
            @Value("${app.seed.test-data-enabled:${SEED_TEST_DATA:false}}") boolean enabled,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            CandidateRepository candidateRepository,
            StaffingEntityRepository staffingEntityRepository,
            JobPostingRepository jobPostingRepository,
            ApplicationRepository applicationRepository,
            InterviewRepository interviewRepository,
            OfferRepository offerRepository,
            InternLifecycleRepository lifecycleRepository,
            WorkAuthorizationRecordRepository workAuthRepository,
            JdbcTemplate jdbc,
            PlatformTransactionManager txManager) {
        this.enabled = enabled;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.candidateRepository = candidateRepository;
        this.staffingEntityRepository = staffingEntityRepository;
        this.jobPostingRepository = jobPostingRepository;
        this.applicationRepository = applicationRepository;
        this.interviewRepository = interviewRepository;
        this.offerRepository = offerRepository;
        this.lifecycleRepository = lifecycleRepository;
        this.workAuthRepository = workAuthRepository;
        this.jdbc = jdbc;
        this.txn = new TransactionTemplate(txManager);
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            log.info("{} SEED_TEST_DATA not set, skipping.", LOG_TAG);
            return;
        }
        log.info("{} Seeding dev test data (SEED_TEST_DATA=true)", LOG_TAG);

        User erm;
        User trainer, evaluator, manager;
        StaffingEntity entity;
        JobPosting posting;
        try {
            // Phase 8.3.3 — scope the new interns to the real developer
            // ERM (erm@anvicorp.test). Fall back to any ERM-role user if
            // that's not provisioned yet, but log a warning so the
            // operator knows seeded interns won't show under their
            // expected scope.
            erm = resolveRealErm();
            if (erm == null) {
                log.warn("{} No user with role ERM found in DB; cannot seed "
                        + "interns. Create an ERM user first and re-run.",
                        LOG_TAG);
                return;
            }

            trainer = findOrCreateStaffUser(TRAINER_EMAIL, "Test Trainer", UserRole.TRAINER, true);
            evaluator = findOrCreateStaffUser(EVALUATOR_EMAIL, "Test Evaluator", UserRole.REPORTING_MANAGER, true);
            manager = findOrCreateStaffUser(MANAGER_EMAIL, "Test Manager", UserRole.MANAGER, true);

            entity = findOrCreateEntity();
            posting = findOrCreatePosting(entity, erm);
        } catch (Exception e) {
            log.warn("{} Supporting fixtures failed (non-fatal — skipping intern seed): {}",
                    LOG_TAG, e.getMessage(), e);
            return;
        }

        final User ermF = erm;
        final User trainerF = trainer;
        final User evaluatorF = evaluator;
        final User managerF = manager;
        final JobPosting postingF = posting;

        int fullyReady = 0;
        int failed = 0;
        for (InternSpec spec : INTERN_SPECS) {
            ChainResult result;
            try {
                // Phase 8.3.3 — wrap the whole intern chain in a single
                // transaction. If any step throws, every prior INSERT in
                // the chain rolls back together, leaving no orphan rows
                // for the next boot to trip over.
                result = txn.execute(status -> {
                    try {
                        return seedIntern(spec, postingF, ermF, trainerF,
                                evaluatorF, managerF);
                    } catch (RuntimeException re) {
                        status.setRollbackOnly();
                        throw re;
                    }
                });
            } catch (Exception e) {
                log.warn("{} Intern {} seed unexpected failure (non-fatal): {}",
                        LOG_TAG, spec.email, e.getMessage(), e);
                continue;
            }
            if (result == null) {
                failed++;
                continue;
            }
            log.info("{}   {}: {}", LOG_TAG, spec.fullName, result.summary());
            if (result.isReady()) fullyReady++;
            else failed++;
        }

        log.info("{} Seed complete. {} fully ready in Pending Document "
                + "Assignment tab, {} incomplete.",
                LOG_TAG, fullyReady, failed);
    }

    /** Phase 8.3.3 — resolve the ERM the seeded interns are scoped to.
     *  Prefers {@link #REAL_ERM_EMAIL} (erm@anvicorp.test); falls back to
     *  any user carrying the ERM role. Returns null when neither is
     *  available — the caller bails with a friendly log in that case. */
    private User resolveRealErm() {
        Optional<User> primary = userRepository.findByEmail(REAL_ERM_EMAIL);
        if (primary.isPresent()) {
            log.info("{}   ERM scope → {} (id={})", LOG_TAG,
                    REAL_ERM_EMAIL, primary.get().getId());
            return primary.get();
        }
        List<User> erms = userRepository.findByRole(UserRole.ERM);
        if (erms.isEmpty()) return null;
        User fallback = erms.get(0);
        log.warn("{}   {} not found; falling back to ERM user {} (id={}). "
                + "Seeded interns may appear under a different scope than "
                + "expected.", LOG_TAG, REAL_ERM_EMAIL,
                fallback.getEmail(), fallback.getId());
        return fallback;
    }

    // ── Supporting users + entity + posting ────────────────────────────────

    private User findOrCreateStaffUser(String email, String fullName, UserRole role,
                                        boolean setZoomEmail) {
        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            log.info("{}   staff {} → already exists, skipped", LOG_TAG, email);
            return existing.get();
        }
        User u = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(STAFF_PASSWORD))
                .fullName(fullName)
                .roles(EnumSet.of(role))
                .emailVerified(true)
                .active(true)
                .lifecycleStatus(InternLifecycleStatus.REGISTERED)
                .build();
        if (setZoomEmail) {
            // Mirror the staff user's email as their Zoom host email so the
            // interview + meeting flows have a valid host id when they fire
            // against the seeded users.
            u.setZoomEmail(email);
        }
        u = userRepository.save(u);
        log.info("{}   staff {} → CREATED (id={})", LOG_TAG, email, u.getId());
        return u;
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
                    log.info("{}   staffing entity \"{}\" → CREATED", LOG_TAG, TEST_ENTITY_NAME);
                    return e;
                });
    }

    private JobPosting findOrCreatePosting(StaffingEntity entity, User erm) {
        return jobPostingRepository.findBySlug(TEST_POSTING_SLUG)
                .orElseGet(() -> {
                    JobPosting p = JobPosting.builder()
                            .entity(entity)
                            .slug(TEST_POSTING_SLUG)
                            .title("Java Full Stack Developer Intern")
                            .description("Build features end-to-end across our Java 21 / Spring Boot 3 "
                                    + "backend and Next.js 14 frontend. 12-month internship.")
                            .requirements("Java fundamentals, basic SQL, comfort with one frontend framework. "
                                    + "Bonus: Spring Boot, JPA, React.")
                            .location("Remote (US)")
                            .employmentType(EmploymentType.INTERNSHIP)
                            .status(JobPostingStatus.OPEN)
                            .publishedById(erm.getId())
                            .publishedAt(Instant.now())
                            .build();
                    p = jobPostingRepository.save(p);
                    log.info("{}   job posting \"{}\" → CREATED (id={})",
                            LOG_TAG, TEST_POSTING_SLUG, p.getId());
                    return p;
                });
    }

    // ── Per-intern chain (self-healing) ────────────────────────────────────

    private enum StepStatus { CREATED, EXISTING, FAILED, SKIPPED }

    /** Tracker for one intern's chain: which step landed CREATED vs
     *  EXISTING vs FAILED. {@link #isReady()} checks the load-bearing
     *  trio (User + InternLifecycle + reporting structure) since those
     *  are what the Pending Document Assignment tab actually queries. */
    private static final class ChainResult {
        boolean ready;
        final java.util.LinkedHashMap<String, StepStatus> steps =
                new java.util.LinkedHashMap<>();
        String failedAt;
        String error;

        void mark(String step, StepStatus s) { steps.put(step, s); }

        void fail(String step, String msg) {
            failedAt = step;
            error = msg;
            mark(step, StepStatus.FAILED);
        }

        boolean isReady() { return ready; }

        String summary() {
            StringBuilder sb = new StringBuilder();
            steps.forEach((k, v) -> {
                sb.append(k).append(switch (v) {
                    case CREATED -> "+";  // freshly created this run
                    case EXISTING -> "✓"; // already there
                    case FAILED -> "✗";
                    case SKIPPED -> "-";
                }).append(' ');
            });
            if (failedAt != null) {
                sb.append("— FAILED at ").append(failedAt).append(": ").append(error);
            } else if (ready) {
                sb.append("— ready");
            } else {
                sb.append("— incomplete");
            }
            return sb.toString().trim();
        }
    }

    /**
     * Walk the full chain top-down; for each step, find or create the row
     * and record the step status. A failure on any step logs + breaks the
     * chain, but rows committed by earlier steps stay in place so the next
     * boot can pick up where this run left off. No surrounding
     * {@code @Transactional} — each repository.save() commits on its own.
     */
    private ChainResult seedIntern(InternSpec spec, JobPosting posting,
                                    User erm, User trainer, User evaluator, User manager) {
        ChainResult result = new ChainResult();
        Instant now = Instant.now();
        Instant appliedAt = now.minus(30, ChronoUnit.DAYS);
        Instant interviewedAt = now.minus(21, ChronoUnit.DAYS);
        Instant offerSentAt = now.minus(14, ChronoUnit.DAYS);
        Instant signedAt = now.minus(spec.signedDaysAgo, ChronoUnit.DAYS);
        LocalDate startDate = LocalDate.now().plus(14, ChronoUnit.DAYS);

        // 1) User
        User user;
        try {
            Optional<User> existing = userRepository.findByEmail(spec.email);
            if (existing.isPresent()) {
                user = existing.get();
                result.mark("User", StepStatus.EXISTING);
            } else {
                user = User.builder()
                        .email(spec.email)
                        .passwordHash(passwordEncoder.encode(INTERN_PASSWORD))
                        .fullName(spec.fullName)
                        .phoneNumber(spec.phone)
                        .roles(EnumSet.of(UserRole.INTERN))
                        .emailVerified(true)
                        .active(true)
                        .applicantId(nextApplicantId())
                        .applicantIdCreatedAt(appliedAt)
                        .employeeId(nextEmployeeId())
                        .lifecycleStatus(InternLifecycleStatus.EMPLOYEE_ID_CREATED)
                        .build();
                user = userRepository.save(user);
                result.mark("User", StepStatus.CREATED);
            }
        } catch (Exception e) {
            result.fail("User", e.getMessage());
            return result;
        }

        // 2) Candidate
        Candidate candidate;
        try {
            Optional<Candidate> existing = candidateRepository.findByUserId(user.getId());
            if (existing.isPresent()) {
                candidate = existing.get();
                result.mark("Candidate", StepStatus.EXISTING);
            } else {
                candidate = Candidate.builder()
                        .user(user)
                        .legalName(spec.fullName)
                        .preferredName(spec.fullName.split(" ")[0])
                        .school(spec.school)
                        .degree(spec.degree)
                        .education(spec.degree + ", " + spec.school)
                        .skillset(spec.skillset)
                        .authorizedToWork(Boolean.TRUE)
                        .sponsorshipNeeded(spec.workAuthTrack != WorkAuthTrack.OTHER)
                        .expectedTrack(spec.workAuthTrack)
                        .build();
                candidate = candidateRepository.save(candidate);
                result.mark("Candidate", StepStatus.CREATED);
            }
        } catch (Exception e) {
            result.fail("Candidate", e.getMessage());
            return result;
        }

        // 3) Application — find by (candidate_id, posting_id) since
        //    applications has UNIQUE on that pair.
        Application app;
        try {
            final Candidate candFinal = candidate;
            Optional<Application> existing = applicationRepository
                    .findByCandidateId(candFinal.getId()).stream()
                    .filter(a -> a.getJobPosting() != null
                            && posting.getId().equals(a.getJobPosting().getId()))
                    .findFirst();
            if (existing.isPresent()) {
                app = existing.get();
                result.mark("Application", StepStatus.EXISTING);
            } else {
                app = Application.builder()
                        .candidate(candidate)
                        .jobPosting(posting)
                        // ACCEPTED is the live post-offer status; HIRED /
                        // ONBOARDING / ACTIVE / COMPLETED on this enum are
                        // @Deprecated — Engagement.status owns post-offer.
                        .status(ApplicationStatus.ACCEPTED)
                        .statementOfInterest(spec.statementOfInterest)
                        .ermOwnerId(erm.getId())
                        .lastDecisionReasonCode("SELECTED")
                        .lastDecisionAt(interviewedAt)
                        .lastDecisionById(erm.getId())
                        .recruiterRating(8)
                        .build();
                app = applicationRepository.save(app);
                backdate("applications", "applied_at", app.getId(), appliedAt);
                backdate("applications", "status_updated_at", app.getId(), signedAt);
                result.mark("Application", StepStatus.CREATED);
            }
        } catch (Exception e) {
            result.fail("Application", e.getMessage());
            return result;
        }

        // 4) Interview — find any interview for the application; create if
        //    the chain is missing one. createdBy is NOT NULL (Phase 8.3.1).
        try {
            List<Interview> existing = interviewRepository
                    .findByApplicationIdOrderByScheduledAtDesc(app.getId());
            if (!existing.isEmpty()) {
                result.mark("Interview", StepStatus.EXISTING);
            } else {
                Interview interview = Interview.builder()
                        .application(app)
                        .interviewer(erm)
                        .scheduledAt(interviewedAt)
                        .durationMinutes(60)
                        .type(InterviewType.TECHNICAL)
                        .status(InterviewStatus.COMPLETED)
                        .timezone("America/Chicago")
                        .createdBy(erm.getId())
                        .decision("SELECTED")
                        .feedbackOverallRating(8)
                        .feedbackTechnicalRating(8)
                        .feedbackCommunicationRating(8)
                        .feedbackProblemSolvingRating(8)
                        .feedbackStrengths("Strong fundamentals; thoughtful trade-off discussion.")
                        .feedbackComments("Recommended for offer. Good cultural fit.")
                        .feedbackRecommendation(InterviewRecommendation.HIRE)
                        .feedbackSubmittedAt(interviewedAt.plus(2, ChronoUnit.HOURS))
                        .feedbackSubmittedBy(erm.getId())
                        .applicantVisibleNotes("Thanks for the great conversation!")
                        .technicalScore(8)
                        .communicationScore(8)
                        .culturalFitScore(8)
                        .overallRecommendation("HIRE")
                        .build();
                interviewRepository.save(interview);
                result.mark("Interview", StepStatus.CREATED);
            }
        } catch (Exception e) {
            result.fail("Interview", e.getMessage());
            return result;
        }

        // 5) Offer — find any offer for the application; create if missing.
        try {
            List<Offer> existing = offerRepository
                    .findByApplicationIdOrderByCreatedAtDesc(app.getId());
            if (!existing.isEmpty()) {
                result.mark("Offer", StepStatus.EXISTING);
            } else {
                String envelopeId = "test-envelope-" + slugify(spec.fullName)
                        + "-" + signedAt.getEpochSecond();
                Offer offer = Offer.builder()
                        .application(app)
                        .compensationAmount(new BigDecimal("28.50"))
                        .compensationFrequency(CompensationFrequency.HOURLY)
                        .compensationCurrency("USD")
                        .startDate(startDate)
                        .expectedEndDate(startDate.plusMonths(12))
                        .expiresAt(offerSentAt.plus(30, ChronoUnit.DAYS))
                        .status(OfferStatus.SIGNED)
                        .letterContent("Seeded test offer for " + spec.fullName
                                + ". Not a real employment offer.")
                        .createdBy(erm.getId())
                        .sentAt(offerSentAt)
                        .respondedAt(signedAt)
                        .signedAt(signedAt)
                        .legacyEnvelopeId(envelopeId)
                        .roleTitle("Java Full Stack Developer Intern")
                        .compensationSummary("$28.50 / hr")
                        .worksite("Remote (US)")
                        .expectedHoursPerWeek(40)
                        .build();
                offerRepository.save(offer);
                result.mark("Offer", StepStatus.CREATED);
            }
        } catch (Exception e) {
            result.fail("Offer", e.getMessage());
            return result;
        }

        // 6) InternLifecycle — the load-bearing row. If User exists but
        //    Lifecycle does not (the prior-failure mode this phase fixes),
        //    we build it here and the New Hire List Pending tab lights up.
        InternLifecycle lc;
        try {
            Optional<InternLifecycle> existing =
                    lifecycleRepository.findByUserId(user.getId());
            if (existing.isPresent()) {
                lc = existing.get();
                result.mark("InternLifecycle", StepStatus.EXISTING);
            } else {
                // Mint a fresh employee_id for the lifecycle row, but reuse
                // the user's existing employee_id if it's already set —
                // some chains have the user.employee_id stamped from an
                // earlier partial run.
                String employeeId = user.getEmployeeId() != null
                        ? user.getEmployeeId() : nextEmployeeId();
                if (user.getEmployeeId() == null) {
                    user.setEmployeeId(employeeId);
                    userRepository.save(user);
                }
                lc = InternLifecycle.builder()
                        .userId(user.getId())
                        .employeeId(employeeId)
                        .activeStatus("PROSPECTIVE")
                        .ermId(erm.getId())
                        .trainerId(trainer.getId())
                        .evaluatorId(evaluator.getId())
                        .managerId(manager.getId())
                        .hiredAt(signedAt)
                        .tentativeStartDate(startDate)
                        .reportingStructureComplete(Boolean.TRUE)
                        .reportingStructureCompletedAt(signedAt.plus(1, ChronoUnit.HOURS))
                        .reportingStructureCompletedById(erm.getId())
                        .build();
                lc = lifecycleRepository.save(lc);
                // hired_at, reporting_structure_completed_at, created_at
                // default to NOW() via @PrePersist. Back-date so the
                // "signed > 7d ago" urgent KPI math matches the spec.
                backdate("intern_lifecycles", "hired_at", lc.getId(), signedAt);
                backdate("intern_lifecycles", "reporting_structure_completed_at",
                        lc.getId(), signedAt.plus(1, ChronoUnit.HOURS));
                backdate("intern_lifecycles", "created_at", lc.getId(), signedAt);
                result.mark("InternLifecycle", StepStatus.CREATED);
            }
        } catch (Exception e) {
            result.fail("InternLifecycle", e.getMessage());
            return result;
        }

        // 7) WorkAuthorizationRecord — non-load-bearing for the Pending tab,
        //    but needed for the Compliance Tracker KPIs.
        try {
            Optional<WorkAuthorizationRecord> existing =
                    workAuthRepository.findByUserId(user.getId());
            if (existing.isPresent()) {
                result.mark("WorkAuth", StepStatus.EXISTING);
            } else {
                WorkAuthorizationRecord war = WorkAuthorizationRecord.builder()
                        .userId(user.getId())
                        .workAuthType(spec.workAuthType)
                        .authorizedFrom(LocalDate.now().minusMonths(2))
                        .authorizedUntil(LocalDate.now().plusYears(2))
                        .i983Required(spec.workAuthTrack == WorkAuthTrack.STEM_OPT)
                        .dsoName(spec.dsoName)
                        .dsoEmail(spec.dsoEmail)
                        .dsoPhone(spec.dsoPhone)
                        .lastUpdatedAt(now)
                        .lastUpdatedById(erm.getId())
                        .build();
                workAuthRepository.save(war);
                result.mark("WorkAuth", StepStatus.CREATED);
            }
        } catch (Exception e) {
            // WorkAuth failure is non-blocking for the Pending tab — log
            // and continue marking the chain ready.
            result.fail("WorkAuth", e.getMessage());
            log.warn("{}     WorkAuth failed (non-blocking) for {}: {}",
                    LOG_TAG, spec.email, e.getMessage());
        }

        // Ready = User + Lifecycle present. WorkAuth is nice-to-have.
        result.ready = result.steps.get("User") != StepStatus.FAILED
                && result.steps.get("InternLifecycle") != null
                && result.steps.get("InternLifecycle") != StepStatus.FAILED;
        return result;
    }


    // ── Helpers ─────────────────────────────────────────────────────────────

    private String nextEmployeeId() {
        try {
            Long n = jdbc.queryForObject(
                    "SELECT nextval('skyzen_employee_seq')", Long.class);
            long val = n == null ? 1000 : n;
            int year = LocalDate.now(ZoneOffset.UTC).getYear();
            return String.format("SKZ-EMP-%d-%06d", year, val);
        } catch (Exception e) {
            // Sequence not available — fall back to UUID-suffixed string so the
            // seeder doesn't bail on a fresh DB before SchemaFixupRunner runs.
            return "SKZ-EMP-TEST-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    private String nextApplicantId() {
        try {
            Long n = jdbc.queryForObject(
                    "SELECT nextval('skyzen_applicant_seq')", Long.class);
            long val = n == null ? 1000 : n;
            int year = LocalDate.now(ZoneOffset.UTC).getYear();
            return String.format("SKZ-INT-%d-%06d", year, val);
        } catch (Exception e) {
            return "SKZ-INT-TEST-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    private void backdate(String table, String column, UUID id, Instant target) {
        try {
            jdbc.update("UPDATE " + table + " SET " + column + " = ? WHERE id = ?",
                    java.sql.Timestamp.from(target), id);
        } catch (Exception e) {
            log.warn("{}   backdate {}.{} on {} skipped (non-fatal): {}",
                    LOG_TAG, table, column, id, e.getMessage());
        }
    }

    private static String slugify(String s) {
        return s == null ? "intern"
                : s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    // ── Intern fixtures ─────────────────────────────────────────────────────

    private record InternSpec(
            String email,
            String fullName,
            String phone,
            int signedDaysAgo,
            WorkAuthTrack workAuthTrack,
            String workAuthType,
            String school,
            String degree,
            String skillset,
            String statementOfInterest,
            String dsoName,
            String dsoEmail,
            String dsoPhone
    ) {}

    // Intern specs — INTENTIONALLY EMPTY. The seeder used to create three
    // demo interns (rohan.demo / anjali.demo / vikram.demo @anvicorp.test) +
    // their full pipeline chain (applicant → interview → offer SIGNED →
    // employee id → reporting structure). Disabled because the SEED_TEST_DATA
    // env-var gate wasn't enough — accounts ended up in prod (either the
    // env var was set, or the rows date back to before the gate existed).
    // The supporting fixtures (StaffingEntity / JobPosting / staff users)
    // still get created when SEED_TEST_DATA=true; only intern creation is
    // off. Repopulate this list to re-enable in dev.
    private static final List<InternSpec> INTERN_SPECS = List.of();
}
