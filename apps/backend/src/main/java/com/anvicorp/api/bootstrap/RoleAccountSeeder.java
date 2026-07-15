package com.anvicorp.api.bootstrap;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Bootstraps one login account per staff role (ERM / TRAINER / EVALUATOR /
 * MANAGER / REPORTING_MANAGER) so each dashboard can be reached without
 * relying on the invitation-link activation flow. Complements
 * {@link AdminSeeder} which owns the SUPER_ADMIN identity.
 *
 * <h2>Gating</h2>
 * Off by default. Enable per-environment via
 * {@code app.bootstrap.seed-role-accounts-enabled=true} (env
 * {@code SEED_ROLE_ACCOUNTS=true}). Intentional dev / bootstrap tool —
 * these accounts ship with known passwords, they must be rotated (or
 * these rows deleted) before the platform sees real users.
 *
 * <h2>Idempotency</h2>
 * For each account, if a user already exists at that email the seeder
 * skips it — never overwrites an existing password or role. Same
 * find-by-email guard {@link AdminSeeder} uses.
 *
 * <h2>Order</h2>
 * {@code @Order(2)} — runs immediately after {@link AdminSeeder}
 * ({@code @Order(1)}) so the SUPER_ADMIN row is in place first.
 *
 * <p>Body is wrapped in try/catch (per-account and outer) so a seeding
 * failure logs and continues — never crashes startup, mirroring
 * {@link AdminSeeder}. Each account's save runs in its own short
 * auto-transaction (no class-level {@code @Transactional}).</p>
 */
@Component
@Order(2)
@RequiredArgsConstructor
@Slf4j
public class RoleAccountSeeder implements CommandLineRunner {

    private static final String LOG_TAG = "[RoleAccountSeeder]";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.bootstrap.seed-role-accounts-enabled:false}")
    private boolean enabled;

    /**
     * A single staff seed. Passwords are encoded via {@link PasswordEncoder}
     * before persistence — nothing plaintext ever hits the DB.
     */
    private record RoleAccount(String email, String password, String fullName, UserRole role) {}

    private static final List<RoleAccount> ACCOUNTS = List.of(
            new RoleAccount("erm@anvicorp.com",               "Erm@Anvi2026",       "Anvi ERM",               UserRole.ERM),
            new RoleAccount("trainer@anvicorp.com",           "Trainer@Anvi2026",   "Anvi Trainer",           UserRole.TRAINER),
            new RoleAccount("evaluator@anvicorp.com",         "Evaluator@Anvi2026", "Anvi Evaluator",         UserRole.EVALUATOR),
            new RoleAccount("manager@anvicorp.com",           "Manager@Anvi2026",   "Anvi Manager",           UserRole.MANAGER),
            new RoleAccount("reporting-manager@anvicorp.com", "Rm@Anvi2026",        "Anvi Reporting Manager", UserRole.REPORTING_MANAGER)
    );

    @Override
    public void run(String... args) {
        try {
            if (!enabled) {
                log.info("{} skipped — disabled (set SEED_ROLE_ACCOUNTS=true to enable)", LOG_TAG);
                return;
            }

            int scanned = 0, created = 0, skipped = 0, failed = 0;
            for (RoleAccount acct : ACCOUNTS) {
                scanned++;
                try {
                    String email = acct.email().trim().toLowerCase();
                    Optional<User> existing = userRepository.findByEmail(email);
                    if (existing.isPresent()) {
                        skipped++;
                        log.info("{} skipping {} — already exists (role={}, active={})",
                                LOG_TAG, email, existing.get().getRoles(), existing.get().getActive());
                        continue;
                    }

                    User user = User.builder()
                            .email(email)
                            .passwordHash(passwordEncoder.encode(acct.password()))
                            .fullName(acct.fullName())
                            .roles(EnumSet.of(acct.role()))
                            .active(true)
                            .emailVerified(true)
                            .build();
                    userRepository.save(user);
                    created++;

                    log.info("{} Seeded role account: {} ({})", LOG_TAG, email, acct.role());
                } catch (Exception perAccount) {
                    failed++;
                    log.warn("{} failed to seed {} ({}): {} — continuing with next account",
                            LOG_TAG, acct.email(), acct.role(), perAccount.getMessage(), perAccount);
                }
            }
            log.info("{} done — scanned={} created={} skipped_existing={} failed={}",
                    LOG_TAG, scanned, created, skipped, failed);
        } catch (Exception outer) {
            log.warn("{} failed (non-fatal): {}", LOG_TAG, outer.getMessage(), outer);
        }
    }
}
