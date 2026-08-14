package com.anvicorp.api.bootstrap;

import com.anvicorp.api.config.BrandConfig;
import com.anvicorp.api.dto.project.CreateProjectRequest;
import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.I983Plan;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.entity.StaffingEntity;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ApplicationStatus;
import com.anvicorp.api.enums.CompensationFrequency;
import com.anvicorp.api.enums.DegreeLevel;
import com.anvicorp.api.enums.DsoApprovalStatus;
import com.anvicorp.api.enums.EmploymentType;
import com.anvicorp.api.enums.EngagementStatus;
import com.anvicorp.api.enums.I983Status;
import com.anvicorp.api.enums.JobPostingStatus;
import com.anvicorp.api.enums.OfferStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.enums.WorkAuthTrack;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.EngagementRepository;
import com.anvicorp.api.repository.I983PlanRepository;
import com.anvicorp.api.repository.JobPostingRepository;
import com.anvicorp.api.repository.OfferRepository;
import com.anvicorp.api.repository.ProjectRepository;
import com.anvicorp.api.repository.StaffingEntityRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.service.ProjectService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Dev-only seeder that lets a developer log in immediately as each of the
 * three two-role-workflow personas (Tech Evaluator + Reporting Manager +
 * Intern) without clicking through the admin UI.
 *
 * <h2>Idempotent</h2>
 * Every entity it touches is looked up by a stable identifier (email, slug,
 * project title scoped to engagement) before insert, so re-runs never
 * duplicate. First run logs CREATED for every step; subsequent runs log
 * "already exists, skipped" for everything.
 *
 * <h2>Gated</h2>
 * Activated by {@code app.seed.test-accounts-enabled=true} (or env var
 * {@code SEED_TEST_ACCOUNTS=true}). Default is false — the body returns
 * immediately and nothing is touched. NEVER set true in production.
 *
 * <h2>What it builds</h2>
 * <ol>
 *   <li>Three users (test-eval / test-rm / test-intern).</li>
 *   <li>The full upstream chain so an Engagement row can land:
 *       StaffingEntity → JobPosting → Application → Offer (ACCEPTED).</li>
 *   <li>One Engagement with supervisor=evaluator + reportingManager=RM.</li>
 *   <li>One Project allocated by the evaluator to the intern, via the
 *       existing {@link ProjectService#create} (the only step here that
 *       routes through a service, since the others have no service-layer
 *       blank-slate create method).</li>
 * </ol>
 */
@Component
@Order(10)
@Slf4j
public class TestAccountSeeder implements CommandLineRunner {

    // ── Stable identifiers (idempotency keys) ────────────────────────────────

    private static final String EVAL_EMAIL    = "test-eval@anvicorp.test";
    private static final String RM_EMAIL      = "test-rm@anvicorp.test";
    private static final String INTERN_EMAIL  = "test-intern@anvicorp.test";
    // Aligned with the 8-role-finalize commit's TestRoleUserSeeder so logging
    // in as test-eval / test-rm / test-intern works against either seeder.
    private static final String EVAL_PASSWORD   = "Eval@1234";
    private static final String RM_PASSWORD     = "Rm@1234";
    private static final String INTERN_PASSWORD = "Intern@1";

    // TEST_ENTITY_NAME + TEST_UNIVERSITY_NAME are composed at run() time
    // from BrandConfig.getName() so a clone seeds "{Brand} Test Entity
    // (Seeder)" + "{Brand} Test University" — for Anvi (name="Anvi Corp")
    // this renders byte-identically to the previous hardcoded literals.
    private static final String TEST_ENTITY_NAME_SUFFIX = " Test Entity (Seeder)";
    private static final String TEST_UNIVERSITY_NAME_SUFFIX = " Test University";
    private static final String TEST_POSTING_SLUG = "skyzen-test-seeder-posting";
    private static final String TEST_PROJECT_TITLE = "Test Workspace Project";

    private static final String LOG_TAG = "[TestAccountSeeder]";

    // ── Wiring ───────────────────────────────────────────────────────────────

    private final boolean enabled;
    private final BrandConfig brand;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CandidateRepository candidateRepository;
    private final StaffingEntityRepository staffingEntityRepository;
    private final JobPostingRepository jobPostingRepository;
    private final ApplicationRepository applicationRepository;
    private final OfferRepository offerRepository;
    private final EngagementRepository engagementRepository;
    private final ProjectRepository projectRepository;
    private final ProjectService projectService;
    private final I983PlanRepository i983PlanRepository;

    public TestAccountSeeder(
            @Value("${app.seed.test-accounts-enabled:false}") boolean enabled,
            BrandConfig brand,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            CandidateRepository candidateRepository,
            StaffingEntityRepository staffingEntityRepository,
            JobPostingRepository jobPostingRepository,
            ApplicationRepository applicationRepository,
            OfferRepository offerRepository,
            EngagementRepository engagementRepository,
            ProjectRepository projectRepository,
            ProjectService projectService,
            I983PlanRepository i983PlanRepository) {
        this.enabled = enabled;
        this.brand = brand;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.candidateRepository = candidateRepository;
        this.staffingEntityRepository = staffingEntityRepository;
        this.jobPostingRepository = jobPostingRepository;
        this.applicationRepository = applicationRepository;
        this.offerRepository = offerRepository;
        this.engagementRepository = engagementRepository;
        this.projectRepository = projectRepository;
        this.projectService = projectService;
        this.i983PlanRepository = i983PlanRepository;
    }

    @Override
    public void run(String... args) {
        if (!enabled) return; // Silent no-op in production / default config.
        // Hard-disabled: this seeder's whole point is the test-intern@
        // anvicorp.test account + its Engagement / Project / I-983 chain.
        // The env-var gate (app.seed.test-accounts-enabled) wasn't enough
        // to keep these accounts out of production. Returning early here
        // is the belt-and-suspenders fix. To re-enable in dev, remove
        // these two lines (the body below is intact).
        log.info("{} disabled at source (demo intern + chain). Re-enable by removing the source-level early return.", LOG_TAG);
        if (true) return;
        log.info("{} Seeding test accounts (app.seed.test-accounts-enabled=true)", LOG_TAG);
        try {
            User evaluator = findOrCreateUser(EVAL_EMAIL,    "Test Evaluator",         EVAL_PASSWORD,   UserRole.TRAINER);
            User rm        = findOrCreateUser(RM_EMAIL,      "Test Reporting Manager", RM_PASSWORD,     UserRole.REPORTING_MANAGER);
            User internU   = findOrCreateUser(INTERN_EMAIL,  "Test Intern",            INTERN_PASSWORD, UserRole.INTERN);

            Candidate internCandidate = findOrCreateCandidate(internU);
            StaffingEntity entity = findOrCreateEntity();
            JobPosting posting = findOrCreatePosting(entity);
            Application application = findOrCreateApplication(internCandidate, posting);
            Offer offer = findOrCreateOffer(application, evaluator);
            Engagement engagement = findOrCreateEngagement(
                    application, internCandidate, offer, entity, evaluator, rm);

            findOrCreateProject(internCandidate, engagement, evaluator);
            findOrCreateI983Plan(internCandidate, application, offer, entity, engagement, evaluator);
            log.info("{} Seed complete.", LOG_TAG);
        } catch (Exception e) {
            // Never crash startup on a seeding failure — same defence used by
            // every other bootstrap runner in this package.
            log.warn("{} Seed failed (non-fatal): {}", LOG_TAG, e.getMessage(), e);
        }
    }

    // ── Users ────────────────────────────────────────────────────────────────

    private User findOrCreateUser(String email, String fullName, String rawPassword, UserRole role) {
        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            log.info("{}   user {} → already exists, skipped", LOG_TAG, email);
            return existing.get();
        }
        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .fullName(fullName)
                .roles(EnumSet.of(role))
                .emailVerified(true) // Skip the 6-digit-code gate for seeded test accounts.
                .active(true)
                .build();
        user = userRepository.save(user);
        log.info("{}   user {} → CREATED", LOG_TAG, email);
        return user;
    }

    private Candidate findOrCreateCandidate(User internUser) {
        return candidateRepository.findByUserId(internUser.getId())
                .orElseGet(() -> {
                    Candidate c = Candidate.builder()
                            .user(internUser)
                            .build();
                    c = candidateRepository.save(c);
                    log.info("{}   candidate row for intern → CREATED", LOG_TAG);
                    return c;
                });
    }

    // ── Engagement upstream chain ────────────────────────────────────────────

    private StaffingEntity findOrCreateEntity() {
        // No name-uniqueness constraint on entities — match-by-name still works
        // because we control the test fixture's name string.
        String testEntityName = brand.getName() + TEST_ENTITY_NAME_SUFFIX;
        return staffingEntityRepository.findAll().stream()
                .filter(e -> testEntityName.equals(e.getName()))
                .findFirst()
                .orElseGet(() -> {
                    StaffingEntity e = StaffingEntity.builder()
                            .name(testEntityName)
                            .isActive(true)
                            .build();
                    e = staffingEntityRepository.save(e);
                    log.info("{}   staffing entity \"{}\" → CREATED", LOG_TAG, testEntityName);
                    return e;
                });
    }

    private JobPosting findOrCreatePosting(StaffingEntity entity) {
        return jobPostingRepository.findBySlug(TEST_POSTING_SLUG)
                .orElseGet(() -> {
                    JobPosting p = JobPosting.builder()
                            .entity(entity)
                            .slug(TEST_POSTING_SLUG)
                            .title("Test seeder posting")
                            .description("Seeder-only posting; never published publicly.")
                            .employmentType(EmploymentType.INTERNSHIP)
                            .status(JobPostingStatus.CLOSED)
                            .build();
                    p = jobPostingRepository.save(p);
                    log.info("{}   job posting \"{}\" → CREATED", LOG_TAG, TEST_POSTING_SLUG);
                    return p;
                });
    }

    private Application findOrCreateApplication(Candidate candidate, JobPosting posting) {
        Optional<Application> existing = applicationRepository.findByCandidateId(candidate.getId())
                .stream()
                .filter(a -> a.getJobPosting() != null
                        && posting.getId().equals(a.getJobPosting().getId()))
                .findFirst();
        if (existing.isPresent()) return existing.get();
        Application a = Application.builder()
                .candidate(candidate)
                .jobPosting(posting)
                .status(ApplicationStatus.ACCEPTED)
                .build();
        a = applicationRepository.save(a);
        log.info("{}   application (intern → test posting) → CREATED", LOG_TAG);
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
                            .letterContent("Seeded test offer — not a real employment offer.")
                            .createdBy(createdBy.getId())
                            .respondedAt(now)
                            .build();
                    o = offerRepository.save(o);
                    log.info("{}   offer (ACCEPTED) → CREATED", LOG_TAG);
                    return o;
                });
    }

    private Engagement findOrCreateEngagement(Application application, Candidate candidate,
                                              Offer offer, StaffingEntity entity,
                                              User supervisor, User reportingManager) {
        Optional<Engagement> existing = engagementRepository.findByApplicationId(application.getId());
        Engagement engagement;
        if (existing.isPresent()) {
            engagement = existing.get();
            log.info("{}   engagement → already exists, skipped (id={})", LOG_TAG, engagement.getId());
        } else {
            engagement = Engagement.builder()
                    .application(application)
                    .candidate(candidate)
                    .offer(offer)
                    .entity(entity)
                    .track(WorkAuthTrack.STEM_OPT) // unlocks I-983 + E-Verify tiles for the test intern
                    .status(EngagementStatus.ACTIVE)
                    .plannedStartDate(LocalDate.now())
                    .actualStartDate(LocalDate.now())
                    .supervisor(supervisor)
                    .reportingManager(reportingManager)
                    .createdBy(supervisor.getId())
                    .build();
            engagement = engagementRepository.save(engagement);
            log.info("{}   engagement → CREATED (id={})", LOG_TAG, engagement.getId());
        }

        // Re-stamp supervisor + reporting manager if they drifted (e.g. a prior
        // run created the engagement before the RM existed). Idempotent: save
        // is a no-op when nothing changed.
        boolean dirty = false;
        if (engagement.getSupervisor() == null
                || !supervisor.getId().equals(engagement.getSupervisor().getId())) {
            engagement.setSupervisor(supervisor);
            dirty = true;
        }
        if (engagement.getReportingManager() == null
                || !reportingManager.getId().equals(engagement.getReportingManager().getId())) {
            engagement.setReportingManager(reportingManager);
            dirty = true;
            log.info("{}   reporting manager assigned → CREATED", LOG_TAG);
        } else {
            log.info("{}   reporting manager → already assigned, skipped", LOG_TAG);
        }
        // Heal track on engagements created before the seeder set it — the
        // I-983 plan gate + candidate dashboard tile both key off STEM_OPT.
        if (engagement.getTrack() != WorkAuthTrack.STEM_OPT) {
            engagement.setTrack(WorkAuthTrack.STEM_OPT);
            dirty = true;
            log.info("{}   engagement.track → set to STEM_OPT", LOG_TAG);
        }
        if (dirty) engagementRepository.save(engagement);

        // Mirror the track on the candidate's expectedTrack so dashboard
        // fallbacks that read candidate.expectedTrack also resolve to STEM_OPT.
        if (candidate.getExpectedTrack() != WorkAuthTrack.STEM_OPT) {
            candidate.setExpectedTrack(WorkAuthTrack.STEM_OPT);
            candidateRepository.save(candidate);
        }
        return engagement;
    }

    // ── Project ─────────────────────────────────────────────────────────────

    private void findOrCreateProject(Candidate intern, Engagement engagement, User evaluator) {
        List<Project> existingProjects = projectRepository.findByInternIdWithGraph(intern.getId());
        Optional<Project> match = existingProjects.stream()
                .filter(p -> TEST_PROJECT_TITLE.equals(p.getTitle()))
                .findFirst();
        if (match.isPresent()) {
            log.info("{}   project \"{}\" → already exists, skipped (id={})",
                    LOG_TAG, TEST_PROJECT_TITLE, match.get().getId());
            return;
        }
        // Route through the existing ProjectService.create so the audit row,
        // task-checklist plumbing, and notification side-effect all behave
        // exactly like a real supervisor allocation.
        CreateProjectRequest req = CreateProjectRequest.builder()
                .title(TEST_PROJECT_TITLE)
                .candidateId(intern.getId())
                .description("Seeded project for end-to-end testing of the workspace + two-role workflow.")
                .deliverables("A working solution that exercises the workspace editor, submission, tech approval, and viva sign-off.")
                .build();
        var resp = projectService.create(req, evaluator);
        log.info("{}   project \"{}\" → CREATED (id={})",
                LOG_TAG, TEST_PROJECT_TITLE, resp.getId());
    }

    // ── I-983 training plan ─────────────────────────────────────────────────

    /**
     * Fully populated DRAFT I-983 plan for the test intern so the candidate
     * I-983 page + the ERM training-plans UI have realistic content to render.
     * Idempotent: skipped if the candidate already has any plan row.
     *
     * Built via direct repository.save (matching the established seeder
     * pattern for upstream entities), not {@link com.anvicorp.api.service.I983Service#createPlan}
     * — the service path has been observed to throw if the candidate's
     * application chain isn't a perfect match for its private invariants,
     * and the seeder owns its own data so the looser path is fine here.
     */
    private void findOrCreateI983Plan(Candidate intern, Application application,
                                      Offer offer, StaffingEntity entity,
                                      Engagement engagement, User creator) {
        var existing = i983PlanRepository.findByCandidateIdOrderByCreatedAtDesc(intern.getId());
        if (!existing.isEmpty()) {
            log.info("{}   I-983 plan → already exists, skipped (count={}, latest={})",
                    LOG_TAG, existing.size(), existing.get(0).getId());
            return;
        }

        var internUser = intern.getUser();
        String[] nameParts = splitFullName(internUser != null ? internUser.getFullName() : null);
        LocalDate today = LocalDate.now();

        I983Plan plan = I983Plan.builder()
                .candidate(intern)
                .application(application)
                .offer(offer)
                .engagement(engagement)
                .entity(entity)
                .status(I983Status.DRAFT)
                .dsoApprovalStatus(DsoApprovalStatus.NOT_SUBMITTED)

                // Section 1 — Student information
                .studentLastName(nameParts[1])
                .studentFirstName(nameParts[0])
                .studentEmail(internUser != null ? internUser.getEmail() : null)
                .sevisId("N0012345678")
                .uscisNumber("123456789")
                .degreeAwarded("Master of Science in Computer Science")
                .degreeLevel(DegreeLevel.MASTERS)
                .universityName(brand.getName() + TEST_UNIVERSITY_NAME_SUFFIX)
                .universityCipCode("11.0701") // Computer Science CIP code
                .dateOfDegreeAward(today.minusMonths(3))
                .optStartDate(today.minusMonths(1))
                .optEndDate(today.plusMonths(11))

                // Section 2 — Employer information (mirrors the test
                // StaffingEntity created earlier in the seeder run).
                .employerName(entity.getName())
                .employerEin("12-3456789")
                .employerAddress("100 Test Street, Suite 200\nSan Francisco, CA 94105")
                .employerWebsite(brand.getWebsiteUrl())
                .employerNaicsCode("541512") // Computer Systems Design Services
                .employerNumberOfFullTimeEmployees(45)
                .employerOfficialName(creator.getFullName())
                .employerOfficialTitle("Technical Evaluator")
                .employerOfficialEmail(creator.getEmail())
                .employerOfficialPhone("+1-415-555-0199")

                // Section 3 — Training program
                .jobTitle("Software Engineering Intern (STEM OPT)")
                .trainingStartDate(today)
                .trainingEndDate(today.plusMonths(11))
                .hoursPerWeek(40)
                .compensationAmount(new java.math.BigDecimal("75000.00"))
                .compensationFrequency(CompensationFrequency.YEARLY)
                .compensationCurrency("USD")
                .supervisorName(creator.getFullName())
                .supervisorTitle("Technical Evaluator")
                .supervisorEmail(creator.getEmail())
                .supervisorPhone("+1-415-555-0199")

                // Section 4 — Training program narrative
                .trainingProgramDescription(
                        "Hands-on training across the full-stack Anvi Careers platform — "
                        + "Java 21 / Spring Boot 3 on the backend, Next.js 14 / TypeScript "
                        + "on the frontend. Intern owns small, well-scoped features end-to-end "
                        + "with regular code review, biweekly evaluation sessions, and a "
                        + "supervised project review cycle.")
                .howTrainingRelatesToDegree(
                        "Direct application of distributed-systems, web-architecture, and "
                        + "data-modeling coursework. Intern's MS coursework in software "
                        + "engineering and database systems maps to ongoing project work "
                        + "on REST APIs, JPA/Hibernate persistence, and React-based UIs.")
                .trainingGoalsAndObjectives(
                        "1. Ship at least three production-grade features under code review.\n"
                        + "2. Demonstrate proficiency in JPA query optimization and Spring "
                        + "Security configuration.\n"
                        + "3. Lead one end-to-end design discussion for a backend module.\n"
                        + "4. Pass the biweekly evaluator rubric for code quality, "
                        + "communication, and ownership.")
                .performanceEvaluationMethod(
                        "Biweekly 1:1 evaluation sessions with the Technical Evaluator "
                        + "covering code quality, delivery cadence, communication, and "
                        + "ownership. Mid-cycle and final written evaluations (12-month "
                        + "and 24-month per DHS guidance).")
                .reportingRequirements(
                        "Weekly status reports submitted via the intern dashboard. "
                        + "Material change reports filed with the DSO within 10 days "
                        + "of any change to employer, position, or training plan.")
                .skillsKnowledgeLearned(
                        "Spring Boot 3, Hibernate 6 / JPA, Postgres query design, REST "
                        + "API best practices, Spring Security + JWT, Next.js 14 App "
                        + "Router, TypeScript, React Server Components, Tailwind, code "
                        + "review hygiene, agile delivery in a small engineering team.")
                .resourcesEquipmentMaterials(
                        "Laptop + dual-monitor setup, IDE licenses (IntelliJ + VS Code), "
                        + "access to staging environments, GitHub repository access, "
                        + "internal documentation and code review tooling.")
                .supervisorCommitments(
                        "1. Weekly 1:1 mentorship sessions.\n"
                        + "2. Biweekly written evaluation feedback.\n"
                        + "3. Code review on every pull request.\n"
                        + "4. Timely DSO notification of any material change to the "
                        + "training plan.")

                .createdBy(creator.getId())
                .build();

        plan = i983PlanRepository.save(plan);
        log.info("{}   I-983 plan → CREATED (id={})", LOG_TAG, plan.getId());
    }

    private static String[] splitFullName(String full) {
        if (full == null || full.isBlank()) return new String[] {"Test", "Intern"};
        String trimmed = full.trim();
        int space = trimmed.indexOf(' ');
        if (space < 0) return new String[] {trimmed, "Intern"};
        return new String[] {trimmed.substring(0, space), trimmed.substring(space + 1)};
    }
}
