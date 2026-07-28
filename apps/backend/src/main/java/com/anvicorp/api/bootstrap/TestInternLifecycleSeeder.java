package com.anvicorp.api.bootstrap;

import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.entity.ProjectAssignment;
import com.anvicorp.api.entity.ProjectSubmission;
import com.anvicorp.api.entity.StaffingEntity;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.InternLifecycleStatus;
import com.anvicorp.api.enums.ProjectAssignmentStatus;
import com.anvicorp.api.enums.ProjectStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.ProjectAssignmentRepository;
import com.anvicorp.api.repository.ProjectRepository;
import com.anvicorp.api.repository.ProjectSubmissionRepository;
import com.anvicorp.api.repository.StaffingEntityRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Seeds 3 already-ACTIVE interns with project assignments at specific
 * completion stages so the trainer-review and evaluator flows can be
 * exercised end-to-end without walking the whole hiring + onboarding
 * pipeline.
 *
 * <h2>What gets created</h2>
 * <ul>
 *   <li>{@code intern-a@anvicorp.com} — 1 project @ {@link ProjectStatus#COMPLETED}</li>
 *   <li>{@code intern-b@anvicorp.com} — 2 projects @ {@link ProjectStatus#COMPLETED}</li>
 *   <li>{@code intern-c@anvicorp.com} — 1 project @ {@link ProjectStatus#SUBMITTED}
 *       (surfaces in the trainer's pending-review queue via
 *       {@code project_submissions.trainer_decision IS NULL})</li>
 * </ul>
 *
 * <p>All three lifecycles are stamped {@code active_status="ACTIVE"} and
 * their {@code trainer_id / evaluator_id / manager_id / erm_id} point at
 * the existing {@link RoleAccountSeeder} accounts. Every project's
 * {@code intern_lifecycle_id} is set so the trainer / evaluator queries
 * (which join through that column) resolve.</p>
 *
 * <h2>Gating — belt + suspenders</h2>
 * <ol>
 *   <li>{@code app.bootstrap.seed-test-interns-enabled} property
 *       (env {@code SEED_TEST_INTERNS}), default FALSE.</li>
 *   <li>{@code @Profile("!prod")} — refuses to load the bean at all when
 *       the active profile is {@code prod}, so even a leaked env var
 *       can't fire it in production.</li>
 * </ol>
 *
 * <h2>Idempotency</h2>
 * Each intern's chain is skipped whole if the user exists ({@code
 * findByEmail}). Trainer / evaluator / manager / ERM accounts must
 * already exist (from {@link RoleAccountSeeder}); the seeder logs a
 * warning and bails without partial writes if any are missing.
 */
@Component
@Order(60)
@Profile("!prod")
@Slf4j
public class TestInternLifecycleSeeder implements CommandLineRunner {

    private static final String LOG_TAG = "[TestInternLifecycleSeeder]";

    /** Documented dev/test password — mirrors the RoleAccountSeeder pattern. */
    private static final String INTERN_PASSWORD = "TestIntern@123";

    // Accounts from RoleAccountSeeder — must exist first.
    private static final String TRAINER_EMAIL = "trainer@anvicorp.com";
    private static final String EVALUATOR_EMAIL = "evaluator@anvicorp.com";
    private static final String MANAGER_EMAIL = "manager@anvicorp.com";
    private static final String ERM_EMAIL = "erm@anvicorp.com";

    // Canonical staffing entity — same lookup SeedJobPostingsRunner uses.
    private static final String CANONICAL_ENTITY_NAME = "Anvi Corp USA";

    private final boolean enabled;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CandidateRepository candidateRepository;
    private final InternLifecycleRepository lifecycleRepository;
    private final StaffingEntityRepository staffingEntityRepository;
    private final ProjectRepository projectRepository;
    private final ProjectAssignmentRepository projectAssignmentRepository;
    private final ProjectSubmissionRepository projectSubmissionRepository;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate txn;

    public TestInternLifecycleSeeder(
            @Value("${app.bootstrap.seed-test-interns-enabled:false}") boolean enabled,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            CandidateRepository candidateRepository,
            InternLifecycleRepository lifecycleRepository,
            StaffingEntityRepository staffingEntityRepository,
            ProjectRepository projectRepository,
            ProjectAssignmentRepository projectAssignmentRepository,
            ProjectSubmissionRepository projectSubmissionRepository,
            JdbcTemplate jdbc,
            PlatformTransactionManager txManager) {
        this.enabled = enabled;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.candidateRepository = candidateRepository;
        this.lifecycleRepository = lifecycleRepository;
        this.staffingEntityRepository = staffingEntityRepository;
        this.projectRepository = projectRepository;
        this.projectAssignmentRepository = projectAssignmentRepository;
        this.projectSubmissionRepository = projectSubmissionRepository;
        this.jdbc = jdbc;
        this.txn = new TransactionTemplate(txManager);
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            log.info("{} skipped — disabled (set SEED_TEST_INTERNS=true to enable)", LOG_TAG);
            return;
        }
        log.info("{} SEED_TEST_INTERNS=true — seeding 3 active test interns", LOG_TAG);

        // Resolve prerequisites. Bail (no partial writes) if any are missing.
        User trainer = userRepository.findByEmail(TRAINER_EMAIL).orElse(null);
        User evaluator = userRepository.findByEmail(EVALUATOR_EMAIL).orElse(null);
        User manager = userRepository.findByEmail(MANAGER_EMAIL).orElse(null);
        User erm = userRepository.findByEmail(ERM_EMAIL).orElse(null);

        if (trainer == null || evaluator == null || manager == null || erm == null) {
            log.warn("{} Missing prerequisite staff account(s): trainer={} evaluator={} "
                    + "manager={} erm={}. Enable SEED_ROLE_ACCOUNTS=true on next boot to "
                    + "create them, then re-run this seeder.",
                    LOG_TAG,
                    trainer != null, evaluator != null, manager != null, erm != null);
            return;
        }

        StaffingEntity entity = ensureCanonicalEntity();
        log.info("{}   scope → entity=\"{}\" trainer={} evaluator={} manager={} erm={}",
                LOG_TAG, entity.getName(),
                trainer.getEmail(), evaluator.getEmail(),
                manager.getEmail(), erm.getEmail());

        // Spec: (email, fullName, projectCount, statusForEachProject)
        List<InternSpec> specs = List.of(
                new InternSpec("intern-a@anvicorp.com", "Test Intern A",
                        1, ProjectStatus.COMPLETED),
                new InternSpec("intern-b@anvicorp.com", "Test Intern B",
                        2, ProjectStatus.COMPLETED),
                new InternSpec("intern-c@anvicorp.com", "Test Intern C",
                        1, ProjectStatus.SUBMITTED)
        );

        int created = 0, skipped = 0, failed = 0;
        for (InternSpec spec : specs) {
            try {
                Boolean didCreate = txn.execute(status -> {
                    try {
                        return seedIntern(spec, trainer, evaluator, manager, erm);
                    } catch (RuntimeException re) {
                        status.setRollbackOnly();
                        throw re;
                    }
                });
                if (Boolean.TRUE.equals(didCreate)) created++;
                else skipped++;
            } catch (Exception e) {
                failed++;
                log.warn("{} intern {} seed failed (non-fatal): {}",
                        LOG_TAG, spec.email, e.getMessage(), e);
            }
        }
        log.info("{} done — created={} skipped_existing={} failed={} (login password for all: {})",
                LOG_TAG, created, skipped, failed, INTERN_PASSWORD);
    }

    /**
     * Returns TRUE if the intern was newly created, FALSE if it already
     * existed and was skipped. Throws to trigger the txn rollback.
     */
    private boolean seedIntern(InternSpec spec, User trainer, User evaluator,
                                User manager, User erm) {
        Optional<User> existing = userRepository.findByEmail(spec.email);
        if (existing.isPresent()) {
            log.info("{}   {} → already exists, skipped", LOG_TAG, spec.email);
            return false;
        }

        Instant now = Instant.now();
        Instant hiredAt = now.minus(45, ChronoUnit.DAYS);
        Instant startedAt = now.minus(30, ChronoUnit.DAYS);
        String employeeId = nextEmployeeId();

        // 1) User (INTERN role, active, verified, lifecycle = ACTIVE_INTERN).
        User user = User.builder()
                .email(spec.email)
                .passwordHash(passwordEncoder.encode(INTERN_PASSWORD))
                .fullName(spec.fullName)
                .roles(EnumSet.of(UserRole.INTERN))
                .active(true)
                .emailVerified(true)
                .employeeId(employeeId)
                .lifecycleStatus(InternLifecycleStatus.ACTIVE_INTERN)
                .build();
        user = userRepository.save(user);

        // 2) Candidate — kept for parity with the real hiring chain so any
        //    downstream query that joins users → candidates keeps resolving.
        Candidate candidate = Candidate.builder()
                .user(user)
                .legalName(spec.fullName)
                .preferredName(spec.fullName.split(" ")[0])
                .education("B.S. Computer Science (seeded)")
                .authorizedToWork(Boolean.TRUE)
                .build();
        candidate = candidateRepository.save(candidate);

        // 3) InternLifecycle — active_status="ACTIVE" is what the roster
        //    queries filter on; reporting_structure_complete gates trainer
        //    / evaluator visibility of the intern.
        InternLifecycle lc = InternLifecycle.builder()
                .userId(user.getId())
                .employeeId(employeeId)
                .activeStatus("ACTIVE")
                .trainerId(trainer.getId())
                .evaluatorId(evaluator.getId())
                .managerId(manager.getId())
                .ermId(erm.getId())
                .hiredAt(hiredAt)
                .startedAt(startedAt)
                .joiningDate(LocalDate.now().minusDays(30))
                .tentativeStartDate(LocalDate.now().minusDays(30))
                .reportingStructureComplete(Boolean.TRUE)
                .reportingStructureCompletedAt(hiredAt.plus(1, ChronoUnit.HOURS))
                .reportingStructureCompletedById(erm.getId())
                .teamNotifiedAt(hiredAt.plus(2, ChronoUnit.HOURS))
                .build();
        lc = lifecycleRepository.save(lc);
        // Back-date the auto-stamped columns so the roster's age-of-record
        // math looks realistic (intern has been active for ~30d).
        backdate("intern_lifecycles", "hired_at", lc.getId(), hiredAt);
        backdate("intern_lifecycles", "created_at", lc.getId(), hiredAt);
        backdate("intern_lifecycles", "reporting_structure_completed_at",
                lc.getId(), hiredAt.plus(1, ChronoUnit.HOURS));

        // 4) Project(s) + ProjectAssignment(s) + ProjectSubmission(s).
        String monthYear = String.format("%04d-%02d",
                LocalDate.now().getYear(), LocalDate.now().getMonthValue());
        for (short i = 1; i <= spec.projectCount; i++) {
            createProjectChain(spec, trainer, lc, user, i, monthYear);
        }

        log.info("{}   {} → CREATED (empId={}, lifecycleId={}, {}x project@{})",
                LOG_TAG, spec.email, employeeId, lc.getId(),
                spec.projectCount, spec.projectStatus);
        return true;
    }

    /** Creates one Project + ProjectAssignment (+ ProjectSubmission when
     *  the target state is SUBMITTED / COMPLETED). */
    private void createProjectChain(InternSpec spec, User trainer,
                                     InternLifecycle lc, User internUser,
                                     short projectNumber, String monthYear) {
        Instant now = Instant.now();
        Instant assignedAt = now.minus(21, ChronoUnit.DAYS);
        Instant projectStartedAt = now.minus(20, ChronoUnit.DAYS);
        Instant submittedAt = now.minus(3, ChronoUnit.DAYS);
        Instant reviewedAt = now.minus(1, ChronoUnit.DAYS);
        LocalDate assignmentDate = LocalDate.now().minusDays(21);

        boolean isCompleted = spec.projectStatus == ProjectStatus.COMPLETED;

        Project project = Project.builder()
                .title(spec.fullName + " — Project " + projectNumber)
                .description("Seeded test project for " + spec.fullName
                        + " (seeder: TestInternLifecycleSeeder). Build the "
                        + "monthly module per the assignment brief.")
                .deliverables("Working code in the intern's project repo, a "
                        + "short design note, and a demo video link.")
                .requirements("Complete the checklist attached to the "
                        + "learning objective. Include tests.")
                .objectives("Ship a working feature end-to-end; demonstrate "
                        + "familiarity with the platform's tech stack.")
                .techStack("Java 21, Spring Boot 3, PostgreSQL, Next.js 14, React 18")
                .assignedBy(trainer)
                .createdById(trainer.getId())
                .status(spec.projectStatus)
                .progressPct(100)
                .startDate(assignmentDate)
                .dueDate(assignmentDate.plusDays(28))
                .startedAt(projectStartedAt)
                .submittedAt(submittedAt)
                .completedAt(isCompleted ? reviewedAt : null)
                .reviewedAt(isCompleted ? reviewedAt : null)
                .reviewedBy(isCompleted ? trainer : null)
                .reviewNotes(isCompleted
                        ? "Nice work — approved on the first submission."
                        : null)
                .internLifecycleId(lc.getId())
                .monthYear(monthYear)
                .projectNumber(projectNumber)
                .notifyStakeholdersInternal(Boolean.TRUE)
                .ktStatus("DONE")
                .ktCompletedAt(projectStartedAt.minus(1, ChronoUnit.DAYS))
                .ktMarkedById(trainer.getId())
                .build();
        project = projectRepository.save(project);

        ProjectAssignmentStatus assignmentStatus = isCompleted
                ? ProjectAssignmentStatus.COMPLETED
                : ProjectAssignmentStatus.SUBMITTED;

        ProjectAssignment assignment = ProjectAssignment.builder()
                .projectId(project.getId())
                .internId(internUser.getId())
                .assignedById(trainer.getId())
                .assignmentDate(assignmentDate)
                .dueDate(assignmentDate.plusDays(28))
                .remarks("Seeded assignment — see project brief for details.")
                .status(assignmentStatus)
                .accessGranted(Boolean.TRUE)
                .accessGrantedAt(assignedAt.plus(2, ChronoUnit.HOURS))
                .accessGrantedById(trainer.getId())
                .startedAt(projectStartedAt)
                .submittedAt(submittedAt)
                .submissionNotes("Deliverables + demo link included.")
                .build();
        projectAssignmentRepository.save(assignment);

        // ProjectSubmission — SUBMITTED leaves trainer_decision NULL so the
        // row appears in the trainer's pending-review queue. COMPLETED
        // stamps ACCEPT + reviewer so the review is already resolved.
        ProjectSubmission submission = ProjectSubmission.builder()
                .project(project)
                .description("Implemented the assignment per the brief; "
                        + "tests pass locally.")
                .linksJson("[\"https://github.com/example/seeded-repo/pull/1\"]")
                .submittedAt(submittedAt)
                .version(1)
                .technicalScore(isCompleted ? (short) 5 : null)
                .communicationScore(isCompleted ? (short) 5 : null)
                .trainerDecision(isCompleted ? "ACCEPT" : null)
                .trainerFeedback(isCompleted
                        ? "Clean solution, good structure, tests thorough."
                        : null)
                .nextAction(isCompleted ? "NEXT_PROJECT" : null)
                .reviewedAt(isCompleted ? reviewedAt : null)
                .reviewedById(isCompleted ? trainer.getId() : null)
                .completionStatus(isCompleted ? "COMPLETED" : null)
                .build();
        projectSubmissionRepository.save(submission);
    }

    private StaffingEntity ensureCanonicalEntity() {
        return staffingEntityRepository.findByName(CANONICAL_ENTITY_NAME)
                .orElseGet(() -> {
                    StaffingEntity e = StaffingEntity.builder()
                            .name(CANONICAL_ENTITY_NAME)
                            .country("USA")
                            .isActive(true)
                            .build();
                    e = staffingEntityRepository.save(e);
                    log.info("{}   StaffingEntity \"{}\" → CREATED",
                            LOG_TAG, CANONICAL_ENTITY_NAME);
                    return e;
                });
    }

    private String nextEmployeeId() {
        try {
            Long n = jdbc.queryForObject(
                    "SELECT nextval('skyzen_employee_seq')", Long.class);
            long val = n == null ? 1000 : n;
            int year = LocalDate.now(ZoneOffset.UTC).getYear();
            return String.format("ANVI-EMP-%d-%06d", year, val);
        } catch (Exception e) {
            return "ANVI-EMP-TEST-" + UUID.randomUUID().toString().substring(0, 8);
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

    private record InternSpec(
            String email,
            String fullName,
            int projectCount,
            ProjectStatus projectStatus
    ) {}
}
