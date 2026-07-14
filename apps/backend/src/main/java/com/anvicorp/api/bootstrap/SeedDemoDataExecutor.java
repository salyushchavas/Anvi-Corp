package com.anvicorp.api.bootstrap;

import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.Resume;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ApplicationStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.JobPostingRepository;
import com.anvicorp.api.repository.ResumeRepository;
import com.anvicorp.api.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Transactional executor for {@link SeedDemoDataRunner}. Lives in its own bean
 * so the {@code @Transactional} proxy sits between the runner's try/catch and
 * the JPA work — if anything fails, the rollback happens inside this proxy
 * call, the {@link org.springframework.transaction.UnexpectedRollbackException}
 * bubbles out HERE, and the runner's catch swallows it cleanly instead of
 * propagating to Spring Boot's startup machinery.
 *
 * Demo-only. Remove or guard with a profile flag before any non-dev deploy.
 */
@Service
@Profile("!prod") // GAP E4 — paired with SeedDemoDataRunner; demo content only.
@RequiredArgsConstructor
@Slf4j
public class SeedDemoDataExecutor {

    private static final String SENTINEL_EMAIL = "priya.sharma@example.com";
    private static final String DEMO_PASSWORD = "demo12345";

    private final UserRepository userRepository;
    private final CandidateRepository candidateRepository;
    private final ResumeRepository resumeRepository;
    private final JobPostingRepository jobPostingRepository;
    private final ApplicationRepository applicationRepository;
    private final PasswordEncoder passwordEncoder;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${app.resume.storage-path:./uploads/resumes}")
    private String resumeStoragePath;

    /** A candidate to seed: full name + email. */
    private record DemoCandidate(String firstName, String lastName, String email) {
        String fullName() {
            return firstName + " " + lastName;
        }
        String resumeFileName() {
            return firstName + "_" + lastName + "_Resume.pdf";
        }
    }

    /** An application to seed: candidate key + posting slug + status + timestamps + notes. */
    private record DemoApp(
            String candidateEmail,
            String postingSlug,
            ApplicationStatus status,
            int appliedDaysAgo,
            int statusUpdatedDaysAgo,
            String recruiterNotes) {}

    // Demo candidates — INTENTIONALLY EMPTY. The seeder used to create 10
    // applicants (priya.sharma, marcus.chen, aisha.patel, jamal.williams,
    // lin.zhou, devon.king, sarah.kim, tom.garcia, olivia.brown,
    // rachel.lee — all @example.com, role INTERN). Disabled because the
    // @Profile("!prod") guard wasn't enough — the accounts ended up in
    // production anyway (env mis-configuration or pre-guard seeding).
    // To re-enable for local demo, repopulate this list AND the
    // APPLICATIONS list below.
    private static final List<DemoCandidate> CANDIDATES = List.of();

    /*
     * Status mapping (spec name -> backend enum value):
     *   OFFER_EXTENDED -> OFFERED
     *   HIRED          -> ACCEPTED
     *   INTERVIEWED    -> INTERVIEWED  (matches backend)
     *
     * The other spec statuses (APPLIED / SHORTLISTED / INTERVIEW_SCHEDULED /
     * REJECTED) are identical to backend values.
     */
    // Applications — INTENTIONALLY EMPTY. Paired with the disabled
    // CANDIDATES list above. The seeder's seed() method is a no-op
    // when CANDIDATES.isEmpty() (the candidate-build loop is the first
    // step). See CANDIDATES comment above for re-enable instructions.
    private static final List<DemoApp> APPLICATIONS = List.of();

    @Transactional
    public void seed() {
        log.info("Starting demo data seeder...");
        if (userRepository.existsByEmail(SENTINEL_EMAIL)) {
            log.info("Demo data already seeded ({} exists), skipping.", SENTINEL_EMAIL);
            return;
        }

        // Resolve postings up-front. If any are missing, the SeedJobPostingsRunner
        // hasn't run yet (or someone deleted them) — fail loudly.
        Map<String, JobPosting> postings = new HashMap<>();
        for (String slug : List.of("backend-developer-intern", "frontend-developer-intern", "cloud-engineer-intern")) {
            JobPosting p = jobPostingRepository.findBySlug(slug)
                    .orElseThrow(() -> new IllegalStateException(
                            "Demo seeder needs posting '" + slug + "' — run SeedJobPostingsRunner first."));
            postings.put(slug, p);
        }

        // 1) Candidates + Users
        Map<String, Candidate> candidatesByEmail = new HashMap<>();
        for (DemoCandidate dc : CANDIDATES) {
            User u = User.builder()
                    .email(dc.email())
                    .passwordHash(passwordEncoder.encode(DEMO_PASSWORD))
                    .fullName(dc.fullName())
                    .roles(EnumSet.of(UserRole.INTERN))
                    .build();
            u = userRepository.save(u);

            Candidate c = Candidate.builder()
                    .user(u)
                    .build();
            c = candidateRepository.save(c);
            candidatesByEmail.put(dc.email(), c);
        }

        // 2) Resumes (one per candidate, marked default)
        Map<String, Resume> resumesByEmail = new HashMap<>();
        for (DemoCandidate dc : CANDIDATES) {
            Candidate c = candidatesByEmail.get(dc.email());
            String stored = UUID.randomUUID() + ".pdf";
            long size = 145_000L + ThreadLocalRandom.current().nextLong(235_000L); // 145 KB .. ~380 KB
            String path = resumeStoragePath.replaceAll("/+$", "") + "/" + stored;

            Resume r = Resume.builder()
                    .candidate(c)
                    .fileName(dc.resumeFileName())
                    .storedFileName(stored)
                    .filePath(path)
                    .fileUrl(stored)
                    .fileSize(size)
                    .contentType("application/pdf")
                    .version(1)
                    .isDefault(true)
                    .build();
            r = resumeRepository.save(r);
            resumesByEmail.put(dc.email(), r);

            // Mirror what ResumeService does — point Candidate.defaultResumeId at it.
            c.setDefaultResumeId(r.getId());
            candidateRepository.save(c);
        }

        // 3) Applications, with historical timestamps applied via bulk UPDATE
        //    (bypasses @PrePersist / @PreUpdate which would otherwise stamp "now").
        Instant now = Instant.now();
        List<UUID> savedIds = new ArrayList<>();
        List<Instant[]> savedTimestamps = new ArrayList<>();

        for (DemoApp da : APPLICATIONS) {
            Candidate c = candidatesByEmail.get(da.candidateEmail());
            JobPosting p = postings.get(da.postingSlug());
            Resume r = resumesByEmail.get(da.candidateEmail());
            if (c == null || p == null || r == null) {
                throw new IllegalStateException("Demo data wiring error for " + da.candidateEmail() + " -> " + da.postingSlug());
            }

            Application app = Application.builder()
                    .candidate(c)
                    .jobPosting(p)
                    .resume(r)
                    .status(da.status())
                    .recruiterNotes(da.recruiterNotes())
                    .build();
            app = applicationRepository.save(app);

            Instant applied = now.minus(da.appliedDaysAgo(), ChronoUnit.DAYS);
            Instant updated = now.minus(da.statusUpdatedDaysAgo(), ChronoUnit.DAYS);
            savedIds.add(app.getId());
            savedTimestamps.add(new Instant[] { applied, updated });
        }

        // Flush so the rows exist before the bulk UPDATE runs against them.
        entityManager.flush();

        for (int i = 0; i < savedIds.size(); i++) {
            UUID id = savedIds.get(i);
            Instant[] ts = savedTimestamps.get(i);
            entityManager.createQuery(
                    "UPDATE Application a " +
                    "SET a.appliedAt = :applied, a.statusUpdatedAt = :updated " +
                    "WHERE a.id = :id")
                    .setParameter("applied", ts[0])
                    .setParameter("updated", ts[1])
                    .setParameter("id", id)
                    .executeUpdate();
        }
        // Bulk UPDATE bypasses the persistence context; clear so any later reads
        // in this transaction see the new timestamps.
        entityManager.clear();

        log.info("Demo data seeded: {} candidates, {} resumes, {} applications across 7 statuses",
                CANDIDATES.size(), CANDIDATES.size(), APPLICATIONS.size());
        log.info("Demo candidate password: {} (all candidates share this for demo purposes)", DEMO_PASSWORD);
        log.warn("DO NOT enable demo data in production — these are sample accounts with a known password");
    }
}
