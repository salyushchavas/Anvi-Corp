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
import java.util.Optional;

/**
 * Bootstraps the initial SUPER_ADMIN user from {@code admin.email} /
 * {@code admin.password} properties. Idempotent — exits if a user already
 * exists at {@code admin.email}, regardless of their current role set.
 *
 * <h2>Why find-by-email, not find-by-role</h2>
 * Pre-SUPER_ADMIN split, "any user with OPERATIONS" was a fine idempotency
 * check because OPERATIONS was the unique god-mode role. After the split,
 * OPERATIONS is the recruiter/ERM operational role and there can be many of
 * them — checking "is there any SUPER_ADMIN?" would still work, but
 * checking "is there a user at admin.email?" is what we actually want:
 * the seeder owns one specific identity, not the SUPER_ADMIN slot in general.
 * If that identity exists, {@link SuperAdminPromotionRunner} handled (or will
 * handle) flipping their role; the seeder's job is only to create them when
 * absent.
 *
 * <h2>Order</h2>
 * {@code @Order(1)} — runs after {@link UserRoleMigrationRunner} (PRECEDENCE+1)
 * and {@link SuperAdminPromotionRunner} (PRECEDENCE+2), so existing accounts
 * have already been migrated by the time we ask whether one exists.
 *
 * Body is wrapped in try/catch so a seeding failure logs a WARN and never
 * crashes startup. No class-level {@code @Transactional}: the single save
 * runs in its own short auto-transaction, avoiding the commit-time
 * {@code UnexpectedRollbackException} trap where Spring rolls back after we've
 * caught the underlying JPA exception.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class AdminSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${admin.email}")
    private String adminEmail;

    @Value("${admin.password}")
    private String adminPassword;

    @Override
    public void run(String... args) {
        try {
            if (adminEmail == null || adminEmail.isBlank()) {
                log.warn("AdminSeeder: admin.email is not set, skipping bootstrap");
                return;
            }
            String email = adminEmail.trim().toLowerCase();

            Optional<User> existing = userRepository.findByEmail(email);
            if (existing.isPresent()) {
                // SuperAdminPromotionRunner already handled the role flip; we
                // don't second-guess it here.
                return;
            }

            User admin = User.builder()
                    .email(email)
                    .passwordHash(passwordEncoder.encode(adminPassword))
                    .fullName("Bootstrap Super Admin")
                    .roles(EnumSet.of(UserRole.SUPER_ADMIN))
                    .build();
            userRepository.save(admin);

            log.warn("Bootstrap SUPER_ADMIN user created at {} — CHANGE PASSWORD IMMEDIATELY "
                    + "in any non-dev environment", email);
        } catch (Exception e) {
            log.warn("Admin seeder failed (non-fatal): {}", e.getMessage(), e);
        }
    }
}
