package com.anvicorp.api.bootstrap;

import com.anvicorp.api.mail.entity.MailAccount;
import com.anvicorp.api.mail.entity.MailAccountStatus;
import com.anvicorp.api.mail.entity.MailDomain;
import com.anvicorp.api.mail.entity.MailRole;
import com.anvicorp.api.mail.repository.MailAccountRepository;
import com.anvicorp.api.mail.repository.MailDomainRepository;
import com.anvicorp.api.notification.NotificationSenderRoles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Provisions the acting-staff mail_accounts the BridgingEmailProvider
 * relies on when routing an internal notification's FROM address.
 *
 * <p><b>Why this seeder exists</b>: {@code BridgingEmailProvider}'s
 * fifth guard resolves the sender by
 * {@code mailAccountRepository.findByLocalPartAndDomain_Id(senderLocalPart,
 * defaultDomain.id)}. The sender local-part comes from
 * {@link NotificationSenderRoles} — one of {@code noreply / erm / trainer
 * / evaluator / manager}. If the corresponding {@code mail_accounts}
 * row doesn't exist, the guard returns false and the entire notification
 * silently falls through to raw SMTP → the intern's personal Gmail.
 * Every List-2A internal-mail event was hitting this because no seeder
 * was previously provisioning these role mailboxes — {@code RoleAccountSeeder}
 * only creates {@code users} rows (careers login), and
 * {@code MailAdminSeeder} only seeds one SUPER_ADMIN mailbox.</p>
 *
 * <p><b>Idempotent</b>: on each boot, for each role local-part, checks
 * {@code mail_accounts} on the default domain and inserts only if
 * missing. A password hash is required by the schema (NOT NULL), so we
 * store a BCrypt of a random long token that's discarded — these
 * mailboxes are server-side sender identities, not human logins.
 * {@code mustChangePassword=true} plus {@code requireChangeOnFirstLogin=true}
 * so if a human ever does try to log in with a leaked password they're
 * force-rotated immediately.</p>
 *
 * <p><b>Domain</b>: uses whichever domain {@link MailAdminSeeder} /
 * {@code SchemaFixupRunner} left as the default — first ACTIVE domain
 * by name lookup, or seeds {@code anvicorp.com} as a last resort.</p>
 *
 * <p><b>Ordering</b>: runs at {@code @Order(8)} — after
 * {@link MailAdminSeeder} ({@code @Order(7)}) so the domain row is
 * definitely present.</p>
 */
@Component
@Order(8)
@RequiredArgsConstructor
@Slf4j
public class MailRoleAccountSeeder implements CommandLineRunner {

    private static final String LOG_TAG = "[MailRoleAccountSeeder]";
    private static final String DEFAULT_DOMAIN = "anvicorp.com";

    /** One mailbox per bridge sender role, plus reporting-manager for
     *  future viva-scheduling flows. Kept aligned with
     *  {@link NotificationSenderRoles}. */
    private static final List<RoleMailbox> ROLE_MAILBOXES = List.of(
            new RoleMailbox(NotificationSenderRoles.NOREPLY,   "Anvi (No Reply)"),
            new RoleMailbox(NotificationSenderRoles.ERM,       "Anvi ERM"),
            new RoleMailbox(NotificationSenderRoles.TRAINER,   "Anvi Trainer"),
            new RoleMailbox(NotificationSenderRoles.EVALUATOR, "Anvi Evaluator"),
            new RoleMailbox(NotificationSenderRoles.MANAGER,   "Anvi Manager"),
            new RoleMailbox("reporting-manager",               "Anvi Reporting Manager"));

    private record RoleMailbox(String localPart, String displayName) {}

    private final MailDomainRepository domainRepository;
    private final MailAccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        try {
            MailDomain domain = resolveOrSeedDefaultDomain();
            if (domain == null) {
                log.warn("{} no default domain resolvable — cannot seed role mailboxes", LOG_TAG);
                return;
            }
            int created = 0;
            int skipped = 0;
            for (RoleMailbox rm : ROLE_MAILBOXES) {
                try {
                    Optional<MailAccount> existing = accountRepository
                            .findByLocalPartAndDomain_Id(rm.localPart(), domain.getId());
                    if (existing.isPresent()) {
                        skipped++;
                        continue;
                    }
                    // Random discarded password — these mailboxes are
                    // server-side FROM identities, not human logins.
                    String randomPassword = java.util.UUID.randomUUID().toString();
                    accountRepository.save(MailAccount.builder()
                            .domain(domain)
                            .localPart(rm.localPart())
                            .displayName(rm.displayName())
                            .passwordHash(passwordEncoder.encode(randomPassword))
                            .role(MailRole.USER)
                            .status(MailAccountStatus.ACTIVE)
                            .mustChangePassword(true)
                            .requireChangeOnFirstLogin(true)
                            .build());
                    created++;
                    log.info("{} provisioned role mailbox {}@{}",
                            LOG_TAG, rm.localPart(), domain.getName());
                } catch (Exception perAccount) {
                    log.warn("{} failed to seed {}@{}: {} — continuing",
                            LOG_TAG, rm.localPart(), domain.getName(),
                            perAccount.getMessage(), perAccount);
                }
            }
            log.info("{} done — created={} skipped_existing={} domain={}",
                    LOG_TAG, created, skipped, domain.getName());
        } catch (Exception outer) {
            log.warn("{} failed (non-fatal): {}", LOG_TAG, outer.getMessage(), outer);
        }
    }

    private MailDomain resolveOrSeedDefaultDomain() {
        Optional<MailDomain> byName = domainRepository.findByName(DEFAULT_DOMAIN);
        if (byName.isPresent()) return byName.get();
        try {
            MailDomain seeded = domainRepository.save(MailDomain.builder()
                    .name(DEFAULT_DOMAIN)
                    .displayName(DEFAULT_DOMAIN)
                    .active(true)
                    .build());
            log.warn("{} seeded default mail domain '{}'", LOG_TAG, DEFAULT_DOMAIN);
            return seeded;
        } catch (Exception e) {
            log.warn("{} could not seed default domain: {}", LOG_TAG, e.getMessage(), e);
            return null;
        }
    }
}
