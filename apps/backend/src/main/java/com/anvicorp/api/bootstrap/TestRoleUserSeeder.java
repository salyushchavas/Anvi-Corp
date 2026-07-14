package com.anvicorp.api.bootstrap;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Seeds one test user per role with known credentials so every role on the
 * six-role taxonomy is testable from a single consistent credential set.
 *
 * <h2>Idempotent</h2>
 * Looked up by email (case-insensitive via the underlying findByEmail). If a
 * user with the same email exists — regardless of role — the row is left
 * untouched. Re-running the seeder is therefore safe; existing accounts are
 * never overwritten. Pre-existing legacy-role rows are remapped onto the new
 * taxonomy by {@code SchemaFixupRunner} before this seeder runs.
 *
 * <h2>Gated</h2>
 * Off by default. Enable via {@code app.bootstrap.seed-test-role-users-enabled=true}
 * (or env {@code APP_BOOTSTRAP_SEED_TEST_ROLE_USERS_ENABLED=true}). NEVER
 * enable in production — the credentials below are documented in the source
 * tree and commit history.
 *
 * <h2>Test users</h2>
 * <pre>
 *   test-intern@anvicorp.test    Intern@1234    INTERN
 *   test-trainer@anvicorp.test   Trainer@1234   TRAINER
 *   test-rm@anvicorp.test        Rm@1234        REPORTING_MANAGER
 *   test-manager@anvicorp.test   Manager@1234   MANAGER
 *   test-erm@anvicorp.test       Erm@1234       ERM
 *   test-admin@anvicorp.test     Admin@1234     SUPER_ADMIN
 * </pre>
 */
@Component
@Order(20)
@RequiredArgsConstructor
@Slf4j
public class TestRoleUserSeeder implements ApplicationRunner {

    private static final String LOG_TAG = "[TestRoleUserSeeder]";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.bootstrap.seed-test-role-users-enabled:false}")
    private boolean enabled;

    private record TestRoleUser(
            String email,
            String password,
            String fullName,
            UserRole role
    ) {}

    // INTERN entry removed — was: test-intern@anvicorp.test / Intern@1234.
    // The seeder no longer creates any INTERN-role accounts. Staff roles
    // (TRAINER / REPORTING_MANAGER / MANAGER / ERM / SUPER_ADMIN) still
    // get seeded when this seeder is enabled.
    private static final List<TestRoleUser> TEST_USERS = List.of(
            new TestRoleUser("test-trainer@anvicorp.test",  "Trainer@1234", "Test Trainer",  UserRole.TRAINER),
            new TestRoleUser("test-rm@anvicorp.test",       "Rm@1234",      "Test RM",       UserRole.REPORTING_MANAGER),
            new TestRoleUser("test-manager@anvicorp.test",  "Manager@1234", "Test Manager",  UserRole.MANAGER),
            new TestRoleUser("test-erm@anvicorp.test",      "Erm@1234",     "Test ERM",      UserRole.ERM),
            new TestRoleUser("test-admin@anvicorp.test",    "Admin@1234",   "Test Admin",    UserRole.SUPER_ADMIN)
    );

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("{} skipped — disabled", LOG_TAG);
            return;
        }
        int scanned = 0, created = 0, skipped = 0, failed = 0;
        for (TestRoleUser tu : TEST_USERS) {
            scanned++;
            try {
                Optional<User> existing = userRepository.findByEmail(tu.email());
                if (existing.isPresent()) {
                    User u = existing.get();
                    boolean hasExpected = u.getRoles() != null
                            && u.getRoles().contains(tu.role());
                    if (hasExpected) {
                        log.info("{} email {} already exists with role {} — skipping",
                                LOG_TAG, tu.email(), tu.role());
                    } else {
                        log.warn("{} email {} exists with role(s) {}, expected {} "
                                        + "— skipping (manual reconcile required)",
                                LOG_TAG, tu.email(), u.getRoles(), tu.role());
                    }
                    skipped++;
                    continue;
                }
                User u = User.builder()
                        .email(tu.email())
                        .passwordHash(passwordEncoder.encode(tu.password()))
                        .fullName(tu.fullName())
                        .roles(EnumSet.of(tu.role()))
                        .emailVerified(true)
                        .active(true)
                        .build();
                userRepository.save(u);
                created++;
                log.warn("{} CREATED {} / {} (role={}) — for non-production testing only",
                        LOG_TAG, tu.email(), tu.password(), tu.role());
            } catch (Exception e) {
                failed++;
                log.warn("{} CREATE failed for {} (role={}): {}",
                        LOG_TAG, tu.email(), tu.role(), e.getMessage(), e);
            }
        }
        log.info("{} done — scanned={} created={} skipped_existing={} failed={}",
                LOG_TAG, scanned, created, skipped, failed);
    }
}
