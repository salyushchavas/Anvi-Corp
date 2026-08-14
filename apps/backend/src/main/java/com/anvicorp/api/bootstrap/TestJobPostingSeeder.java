package com.anvicorp.api.bootstrap;

import com.anvicorp.api.config.BrandConfig;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.StaffingEntity;
import com.anvicorp.api.enums.EmploymentType;
import com.anvicorp.api.enums.JobPostingStatus;
import com.anvicorp.api.repository.JobPostingRepository;
import com.anvicorp.api.repository.StaffingEntityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Doc-spec sample postings — exactly one INTERNSHIP and one FULL_TIME so the
 * Phase 0 cleaned-slate environment always has both job_type buckets
 * represented. Idempotent by slug; existing rows are left untouched.
 *
 * <p>Runs after {@link CleanSlateRunner} (@Order 10) so it can re-seed
 * the canonical sample set when an operator opts in to a wipe — note
 * that {@code job_postings} is preserved by the wipe, so this seeder is
 * a no-op once the slugs exist.</p>
 *
 * <p>Default ON ({@code app.bootstrap.seed-test-jobs-enabled=true}); flip
 * false in production once real postings replace the samples.</p>
 *
 * <p>Also gated with {@code @Profile("!prod")} — matches the peer
 * {@link SeedJobPostingsRunner} + {@link SeedDemoDataRunner} pattern.
 * The seeder is idempotent by slug, so without this gate a prod
 * {@code DELETE FROM job_postings WHERE slug='senior-engineer'} would
 * silently re-appear on the next boot. Belt-and-suspenders alongside
 * the flag: on prod the profile short-circuits bean creation entirely;
 * a mis-set profile still falls back to the {@code enabled} check.</p>
 */
@Component
@Profile("!prod") // Never seed sample postings in production.
@Order(15)
@RequiredArgsConstructor
@Slf4j
public class TestJobPostingSeeder implements ApplicationRunner {

    private static final String LOG_TAG = "[TestJobPostingSeeder]";

    private static final String INTERN_SLUG = "java-spring-boot-developer-intern";
    private static final String FULLTIME_SLUG = "senior-engineer";

    private final StaffingEntityRepository staffingEntityRepository;
    private final JobPostingRepository jobPostingRepository;
    private final BrandConfig brand;

    @Value("${app.bootstrap.seed-test-jobs-enabled:true}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.debug("{} disabled — skipping.", LOG_TAG);
            return;
        }
        try {
            doRun();
        } catch (Exception e) {
            log.warn("{} failed (non-fatal): {}", LOG_TAG, e.getMessage(), e);
        }
    }

    private void doRun() {
        StaffingEntity entity = ensureStaffingEntity();
        int created = 0;
        if (ensurePosting(entity, INTERN_SLUG, this::buildInternship)) created++;
        if (ensurePosting(entity, FULLTIME_SLUG, this::buildFullTime)) created++;
        log.info("{} done. created={} already_present={}",
                LOG_TAG, created, 2 - created);
    }

    private boolean ensurePosting(StaffingEntity entity, String slug,
                                  java.util.function.Function<StaffingEntity, JobPosting> builder) {
        Optional<JobPosting> existing = jobPostingRepository.findBySlug(slug);
        if (existing.isPresent()) return false;
        JobPosting jp = builder.apply(entity);
        jobPostingRepository.save(jp);
        log.info("{} created sample posting slug={} job_type={}",
                LOG_TAG, jp.getSlug(), jp.getEmploymentType());
        return true;
    }

    private JobPosting buildInternship(StaffingEntity entity) {
        Instant now = Instant.now();
        return JobPosting.builder()
                .entity(entity)
                .slug(INTERN_SLUG)
                .title("Java / Spring Boot Developer Intern")
                .description("""
                        12-week paid internship building production Java services on Spring Boot.
                        Remote within the United States. Real customer-facing work — no make-work.
                        """)
                .requirements("""
                        - Currently enrolled in or recently completed a CS / SE degree
                        - Coursework or side projects in Java (Spring Boot a plus)
                        - Familiarity with PostgreSQL or another relational database
                        - Comfort with Git and a unix-y command line
                        """)
                .location("Remote (US)")
                .employmentType(EmploymentType.INTERNSHIP)
                .status(JobPostingStatus.OPEN)
                .publishedAt(now)
                .build();
    }

    private JobPosting buildFullTime(StaffingEntity entity) {
        Instant now = Instant.now();
        return JobPosting.builder()
                .entity(entity)
                .slug(FULLTIME_SLUG)
                .title("Senior Engineer")
                .description("""
                        Full-time senior engineering role. Ownership of major product surfaces from
                        design through ship. Remote within the United States. Ongoing.
                        """)
                .requirements("""
                        - 5+ years building production software in a typed language
                        - Track record of shipping customer-facing systems end-to-end
                        - Strong written communication — async-first team
                        - Comfort with cloud infra (AWS preferred) and CI/CD discipline
                        """)
                .location("Remote (US)")
                .employmentType(EmploymentType.FULL_TIME)
                .status(JobPostingStatus.OPEN)
                .publishedAt(now)
                .build();
    }

    /**
     * Look up the canonical entity by NAME (= BrandConfig.legalName). The
     * old {@code findAll().get(0)} lookup was non-deterministic: if a
     * legacy dev DB had a stray "Stellar USA" row it would win and this
     * seeder would attach the sample "Senior Engineer" posting to the
     * wrong parent. Both this seeder and SeedJobPostingsRunner route
     * through the same brand-derived name so they always agree.
     */
    private StaffingEntity ensureStaffingEntity() {
        String canonicalEntityName = brand.getLegalName();
        return staffingEntityRepository.findByName(canonicalEntityName)
                .orElseGet(() -> {
                    StaffingEntity entity = StaffingEntity.builder()
                            .name(canonicalEntityName)
                            .country("USA")
                            .isActive(true)
                            .build();
                    entity = staffingEntityRepository.save(entity);
                    log.info("{} created default StaffingEntity '{}'",
                            LOG_TAG, canonicalEntityName);
                    return entity;
                });
    }
}
