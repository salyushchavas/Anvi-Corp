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
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Seeds the canonical StaffingEntity (BrandConfig.legalName — "Anvi Corp
 * USA" by default) + sample job postings on first boot.
 *
 * Body is wrapped in try/catch so a seeding failure logs a WARN and never
 * crashes startup. No class-level {@code @Transactional}: {@code saveAll}
 * still batches in its own short auto-transaction, and the per-call auto-tx
 * model avoids the commit-time {@code UnexpectedRollbackException} trap.
 */
@Component
@Profile("!prod") // GAP E4 — demo content; never seeded in production.
@Order(2)
@RequiredArgsConstructor
@Slf4j
public class SeedJobPostingsRunner implements CommandLineRunner {

    private final StaffingEntityRepository staffingEntityRepository;
    private final JobPostingRepository jobPostingRepository;
    private final BrandConfig brand;

    @Override
    public void run(String... args) {
        try {
            doRun();
        } catch (Exception e) {
            log.warn("Job-postings seeder failed (non-fatal): {}", e.getMessage(), e);
        }
    }

    private void doRun() {
        StaffingEntity entity = ensureStaffingEntity();
        if (jobPostingRepository.count() > 0) {
            return;
        }
        Instant now = Instant.now();
        String brandLegalName = entity.getName();

        List<JobPosting> samples = List.of(
                JobPosting.builder()
                        .entity(entity)
                        .slug("backend-developer-intern")
                        .title("Backend Developer Intern")
                        .description(String.format("""
                                Join the %s backend team and ship production code in Java and Spring Boot. \
                                You'll work alongside senior engineers building REST APIs, designing PostgreSQL schemas, \
                                and integrating with cloud services.

                                This 12-week paid internship is fully remote within the United States. Interns are \
                                paired with a mentor and contribute to real customer-facing features from day one — \
                                no make-work or coffee runs.

                                We're looking for builders who enjoy clean code, pragmatic design, and learning the \
                                "why" behind framework conventions, not just the "how".
                                """, brandLegalName))
                        .requirements("""
                                - Currently enrolled in or recently completed a Computer Science / Software \
                                Engineering degree program
                                - Coursework or side-project experience with Java (Spring Boot a plus)
                                - Familiarity with relational databases (PostgreSQL or MySQL)
                                - Comfort with Git, code reviews, and a unix-y command line
                                - Strong written communication — async-first team
                                """)
                        .location("Remote (US)")
                        .employmentType(EmploymentType.INTERNSHIP)
                        .status(JobPostingStatus.OPEN)
                        .publishedAt(now)
                        .build(),

                JobPosting.builder()
                        .entity(entity)
                        .slug("frontend-developer-intern")
                        .title("Frontend Developer Intern")
                        .description(String.format("""
                                Build user-facing experiences with React and Next.js on the %s product team. \
                                You'll own components end-to-end: design hand-off, implementation, accessibility, \
                                and shipping behind feature flags.

                                The codebase is TypeScript-first, with Tailwind for styling and Playwright for \
                                browser tests. We care about real users on slow networks, not just demo-day pixel \
                                perfection.

                                Internship is 12 weeks, paid, US remote. You'll graduate with at least one shipped \
                                feature in production and a portfolio of merged PRs.
                                """, brandLegalName))
                        .requirements("""
                                - Working knowledge of React (hooks, context) and TypeScript
                                - Familiarity with Next.js or another React meta-framework is a plus
                                - Understanding of HTML semantics, accessibility basics, and responsive CSS
                                - Comfort reading API contracts and chaining requests
                                - Curiosity for performance, bundle size, and Core Web Vitals
                                """)
                        .location("Remote (US)")
                        .employmentType(EmploymentType.INTERNSHIP)
                        .status(JobPostingStatus.OPEN)
                        .publishedAt(now)
                        .build(),

                JobPosting.builder()
                        .entity(entity)
                        .slug("cloud-engineer-intern")
                        .title("Cloud Engineer Intern")
                        .description(String.format("""
                                Help %s's platform team operate the infrastructure that runs every customer \
                                workload. You'll work in AWS — ECS, RDS, S3, CloudWatch — and learn what it takes \
                                to keep a 24x7 service reliable.

                                Expect a mix of automation (Terraform, GitHub Actions), incident response shadowing, \
                                and cost / observability projects. You'll write runbooks, not just consume them.

                                This is a 12-week paid, US-remote internship. Strong interns are routinely converted \
                                to full-time offers at the end of the program.
                                """, brandLegalName))
                        .requirements("""
                                - Exposure to a major cloud provider (AWS preferred, GCP/Azure also welcome)
                                - Familiarity with Linux, shell scripting, and Docker fundamentals
                                - Some experience with infrastructure as code (Terraform, CloudFormation, or Pulumi)
                                - Curiosity about networking, IAM, and how distributed systems actually fail
                                - Solid written communication — you'll author postmortems and runbooks
                                """)
                        .location("Remote (US)")
                        .employmentType(EmploymentType.INTERNSHIP)
                        .status(JobPostingStatus.OPEN)
                        .publishedAt(now)
                        .build()
        );

        jobPostingRepository.saveAll(samples);
        log.info("INFO Sample job postings seeded ({} created under entity {})",
                samples.size(), entity.getName());
    }

    /**
     * Look up the canonical entity by NAME (not {@code findAll().get(0)}).
     * The old first-row lookup was non-deterministic — if a legacy dev DB
     * had a stray "Stellar USA" row it would win over the canonical entity
     * and postings would be seeded under the wrong parent. Name = brand
     * legal name, so a clone seeds under its own entity.
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
                    log.info("INFO Default StaffingEntity '{}' seeded",
                            canonicalEntityName);
                    return entity;
                });
    }
}
