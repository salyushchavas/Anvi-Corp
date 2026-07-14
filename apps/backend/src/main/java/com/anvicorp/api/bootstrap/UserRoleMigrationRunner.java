package com.anvicorp.api.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-time PED-§7 role-model migration. Rewrites every {@code user_roles} row
 * from the old six-role taxonomy to the new one:
 *
 * <pre>
 *   RECRUITER, ERM, ADMIN            → OPERATIONS
 *   TECHNICAL_EVALUATOR              → TECHNICAL_EVALUATOR
 *   HR                    → HR  (no change)
 *   CANDIDATE, when user has an ACTIVE engagement → INTERN
 *   CANDIDATE, otherwise             → APPLICANT
 *   EXECUTIVE                        → new; no auto-map from any old role
 * </pre>
 *
 * <h2>Why a runner, not a Flyway migration</h2>
 * The codebase doesn't yet use Flyway/Liquibase ({@code GAP_REPORT.md} flags
 * this for Sprint 8). Until that lands, schema and data fixups live in the
 * {@code bootstrap/*Runner.java} family — same pattern as
 * {@code SchemaFixupRunner}. CRITICAL: this MUST run before any seeder or
 * authentication code touches a user, because Hibernate will fail to
 * deserialize the old enum-string values ({@code 'CANDIDATE'},
 * {@code 'RECRUITER'}, …) against the new {@link com.anvicorp.api.enums.UserRole}.
 *
 * <h2>Idempotency</h2>
 * Native SQL only. Filters by exact old-role strings, so re-running after a
 * successful migration affects 0 rows. Safe to leave in the runner family
 * forever — overhead is one trivial UPDATE per old role.
 *
 * <h2>Order</h2>
 * {@code @Order(HIGHEST_PRECEDENCE + 1)} — runs immediately after
 * {@link SchemaFixupRunner} (HIGHEST_PRECEDENCE), before AdminSeeder (@Order 1),
 * RoleTestUsersSeeder (@Order 3), SeedDemoDataRunner (@Order 4), and all
 * backfill runners. The schema must exist, but no other user-touching code
 * may have run yet.
 *
 * <h2>JWTs</h2>
 * Existing JWTs carry the old role names in their {@code roles} claim. After
 * this migration runs and the next deploy ships, the JWT filter compares old
 * role strings against the new enum values and returns 403 — every user
 * re-logs in. Expected and documented.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
@Slf4j
public class UserRoleMigrationRunner implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        try {
            int total = 0;

            // 0. Drop the stale Hibernate CHECK constraint BEFORE any UPDATEs run.
            //    Originally placed at step 5 (cleanup), but the constraint is
            //    keyed on the OLD enum values — so any UPDATE that writes a NEW
            //    enum string (APPLICANT, INTERN, SUPER_ADMIN, …) explodes with
            //    a check_constraint violation before we ever reach step 5. The
            //    drop MUST happen first. IF EXISTS keeps it idempotent on DBs
            //    where the constraint was never auto-added. Hibernate may
            //    re-add it on next boot against the CURRENT enum values, which
            //    is fine — the new values are what we want.
            try {
                jdbcTemplate.execute(
                        "ALTER TABLE user_roles DROP CONSTRAINT IF EXISTS user_roles_role_check");
            } catch (Exception ce) {
                log.warn("user_roles_role_check drop failed (non-fatal): {}", ce.getMessage());
            }

            // 1. CANDIDATE → INTERN. The 6-role taxonomy collapses pre-hire
            //    and post-hire candidate lifecycle onto INTERN, so the engagement
            //    check that used to split CANDIDATE → INTERN vs APPLICANT is
            //    obsolete. Any legacy CANDIDATE row maps to INTERN flat.
            int hired = jdbcTemplate.update(
                    "UPDATE user_roles SET role = 'INTERN' WHERE role = 'CANDIDATE'");
            total += hired;
            log.info("Role migration: CANDIDATE→INTERN: {} rows", hired);

            // 2. RECRUITER / ADMIN → ERM. Done as separate statements so the
            //    per-bucket counts log cleanly. A user who happened to carry
            //    multiple of these in a transitional state would collapse to
            //    a single ERM row via the (user_id, role) primary-key dedupe.
            //    Note: the legacy 'ERM' role string already maps to the new
            //    ERM value, so no UPDATE is needed for that bucket. Legacy
            //    'OPERATIONS' rows are remapped to ERM by SchemaFixupRunner.
            int ops = 0;
            ops += jdbcTemplate.update(
                    "UPDATE user_roles SET role = 'ERM' WHERE role = 'RECRUITER' "
                            + "AND NOT EXISTS (SELECT 1 FROM user_roles ur2 "
                            + "  WHERE ur2.user_id = user_roles.user_id AND ur2.role = 'ERM')");
            jdbcTemplate.update(
                    "DELETE FROM user_roles WHERE role = 'RECRUITER'");
            ops += jdbcTemplate.update(
                    "UPDATE user_roles SET role = 'ERM' WHERE role = 'ADMIN' "
                            + "AND NOT EXISTS (SELECT 1 FROM user_roles ur2 "
                            + "  WHERE ur2.user_id = user_roles.user_id AND ur2.role = 'ERM')");
            jdbcTemplate.update(
                    "DELETE FROM user_roles WHERE role = 'ADMIN'");
            total += ops;
            log.info("Role migration: RECRUITER/ADMIN→ERM: {} rows promoted, "
                    + "duplicates collapsed", ops);

            // 4. (Step retired.) The original PED-§7 line here renamed
            //     TECHNICAL_EVALUATOR → TECHNICAL_SUPERVISOR. The
            //     8-role-finalize commit reversed that name, so the
            //     equivalent UPDATE now lives in SchemaFixupRunner
            //     (TECHNICAL_SUPERVISOR → TECHNICAL_EVALUATOR + HR_COMPLIANCE
            //     → HR). Keeping this as a no-op comment so the migration
            //     trail is readable.

            // (The stale CHECK-constraint drop that used to live here was moved
            // to step 0 — see the comment there.)

            log.info("Role migration complete: {} total row(s) rewritten. "
                    + "Existing JWTs will fail authorization — users must re-login.", total);

        } catch (Exception e) {
            // Catch-and-log so a migration failure doesn't crash startup. But
            // if this fails on first boot, subsequent reads against user_roles
            // will throw EnumNotFound — the deploy will still be unusable.
            log.error("UserRoleMigrationRunner FAILED — users will not be able to log in. "
                    + "Manual SQL fix required: {}", e.getMessage(), e);
        }
    }
}
