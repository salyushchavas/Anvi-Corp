package com.anvicorp.api.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-time SUPER_ADMIN promotion. The PED §7 fold collapsed the old ADMIN role
 * into OPERATIONS so that former admins are now indistinguishable from
 * recruiters and ERMs. This runner restores the owner-vs-operations split by
 * promoting ONLY the account whose email matches {@code admin.email} from
 * OPERATIONS to SUPER_ADMIN. Bulk promotion of OPERATIONS would re-grant
 * god-mode to recruiters/ERMs — exactly the thing we're undoing.
 *
 * <h2>Promotion rules</h2>
 * Match is by identity (email), not by role. If the {@code admin.email} user:
 * <ul>
 *   <li>does not exist — no-op. AdminSeeder will create it as SUPER_ADMIN next.</li>
 *   <li>already has SUPER_ADMIN — no-op (idempotent).</li>
 *   <li>has OPERATIONS only — replaced with SUPER_ADMIN.</li>
 *   <li>has OPERATIONS plus other roles — OPERATIONS row swapped for SUPER_ADMIN,
 *       the other roles left untouched.</li>
 *   <li>has neither (e.g. EXECUTIVE-only account at the same email) — SUPER_ADMIN
 *       row added alongside the existing role(s).</li>
 * </ul>
 *
 * <h2>Why native SQL</h2>
 * Same reason as {@link UserRoleMigrationRunner}: JPA's enum converter would
 * round-trip through the new {@link com.anvicorp.api.enums.UserRole} enum
 * and tie the migration to whatever value happens to be valid at runtime. We
 * want a raw, deterministic INSERT/DELETE keyed on string values.
 *
 * <h2>Order</h2>
 * {@code @Order(HIGHEST_PRECEDENCE + 2)} — runs after
 * {@link UserRoleMigrationRunner} (HIGHEST_PRECEDENCE + 1) so that the
 * post-§7 OPERATIONS row exists at the {@code admin.email} account, and
 * before AdminSeeder (@Order 1) so the seeder's "is there a SUPER_ADMIN?"
 * idempotency check sees the promoted row.
 *
 * <h2>Re-login</h2>
 * Promoted user's existing JWT still carries OPERATIONS in its {@code roles}
 * claim. SUPER_ADMIN-gated endpoints return 403 until next login. Documented
 * in COMPLIANCE_GATES_CHANGES.md.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@RequiredArgsConstructor
@Slf4j
public class SuperAdminPromotionRunner implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    @Value("${admin.email:}")
    private String adminEmail;

    @Override
    public void run(String... args) {
        try {
            if (adminEmail == null || adminEmail.isBlank()) {
                log.info("SuperAdminPromotionRunner: admin.email not set, skipping");
                return;
            }
            String email = adminEmail.trim().toLowerCase();

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id FROM users WHERE LOWER(email) = ?", email);
            if (rows.isEmpty()) {
                log.info("SuperAdminPromotionRunner: no user at admin.email={} (AdminSeeder "
                        + "will create one as SUPER_ADMIN)", email);
                return;
            }
            UUID userId = (UUID) rows.get(0).get("id");

            boolean alreadySuperAdmin = !jdbcTemplate.queryForList(
                    "SELECT 1 FROM user_roles WHERE user_id = ? AND role = 'SUPER_ADMIN'",
                    userId).isEmpty();
            if (alreadySuperAdmin) {
                log.info("SuperAdminPromotionRunner: user {} already SUPER_ADMIN, no-op", email);
                return;
            }

            boolean hasOperations = !jdbcTemplate.queryForList(
                    "SELECT 1 FROM user_roles WHERE user_id = ? AND role = 'OPERATIONS'",
                    userId).isEmpty();

            int touched;
            String fromRole;
            if (hasOperations) {
                // Promote in place: swap OPERATIONS for SUPER_ADMIN. Use UPDATE
                // rather than DELETE+INSERT so any FK or audit trigger on
                // user_roles sees a single change row.
                touched = jdbcTemplate.update(
                        "UPDATE user_roles SET role = 'SUPER_ADMIN' "
                                + "WHERE user_id = ? AND role = 'OPERATIONS'", userId);
                fromRole = "OPERATIONS";
            } else {
                // No OPERATIONS row — add SUPER_ADMIN alongside whatever exists.
                touched = jdbcTemplate.update(
                        "INSERT INTO user_roles (user_id, role) VALUES (?, 'SUPER_ADMIN')",
                        userId);
                fromRole = "(none)";
            }

            // Forensic breadcrumb: a USER_ROLE_FLIP audit row records the
            // promotion. afterJson is a hand-built JSON string to avoid pulling
            // ObjectMapper into a bootstrap runner. Column names match
            // AuditLog entity (user_id, entity_type, entity_id, after_json,
            // timestamp) — actor here IS the promoted user, since boot-time
            // runners have no Spring Security context.
            try {
                String afterJson = "{\"from\":\"" + fromRole + "\",\"to\":\"SUPER_ADMIN\","
                        + "\"reason\":\"SuperAdminPromotionRunner: identity=admin.email\"}";
                jdbcTemplate.update(
                        "INSERT INTO audit_logs (id, action, entity_type, entity_id, "
                                + "user_id, after_json, timestamp) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                        UUID.randomUUID(),
                        "USER_ROLE_FLIP",
                        "User",
                        userId,
                        userId,
                        afterJson,
                        Timestamp.from(Instant.now()));
            } catch (Exception ae) {
                // Audit insert is best-effort — schema mismatch shouldn't block
                // the promotion itself.
                log.warn("SuperAdminPromotionRunner: audit insert failed (non-fatal): {}",
                        ae.getMessage());
            }

            log.warn("SuperAdminPromotionRunner: promoted {} ({} → SUPER_ADMIN), {} row(s) "
                    + "rewritten. User must RE-LOGIN — existing JWT still carries "
                    + "old roles.", email, fromRole, touched);

        } catch (Exception e) {
            // Mirror UserRoleMigrationRunner: log-and-continue. A failure here
            // leaves admin.email on OPERATIONS, which still authenticates; only
            // SUPER_ADMIN-gated endpoints will reject them.
            log.error("SuperAdminPromotionRunner FAILED — admin.email account remains on "
                    + "OPERATIONS, SUPER_ADMIN endpoints will return 403. Manual SQL "
                    + "fix required: {}", e.getMessage(), e);
        }
    }
}
