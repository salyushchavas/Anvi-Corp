package com.anvicorp.api.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Populates the database with realistic demo candidates, resumes and applications
 * spread across the recruitment pipeline so the Kanban at /careers/erm shows
 * cards in every column for the demo.
 *
 * Idempotent — if the sentinel demo candidate ({@code priya.sharma@example.com})
 * already exists, the whole seeder is a no-op. Otherwise it creates the full set.
 *
 * Demo-only. Remove or guard with a profile flag before any non-dev deploy.
 *
 * This is a thin wrapper around {@link SeedDemoDataExecutor#seed()}. Splitting the
 * transactional work into a separate bean keeps the proxy boundary between the
 * runner's try/catch and the JPA work, so an {@code UnexpectedRollbackException}
 * thrown at commit time is caught here instead of propagating to startup.
 */
@Component
@Profile("!prod") // GAP E4 — demo data; never seeded in production.
@Order(4)
@RequiredArgsConstructor
@Slf4j
public class SeedDemoDataRunner implements CommandLineRunner {

    private final SeedDemoDataExecutor executor;

    @Override
    public void run(String... args) {
        try {
            executor.seed();
        } catch (Exception e) {
            log.warn("Demo data seeder failed (non-fatal): {}", e.getMessage(), e);
        }
    }
}
