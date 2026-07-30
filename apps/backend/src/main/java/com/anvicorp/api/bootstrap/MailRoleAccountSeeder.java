package com.anvicorp.api.bootstrap;

import com.anvicorp.api.mail.entity.MailAccount;
import com.anvicorp.api.mail.entity.MailAccountStatus;
import com.anvicorp.api.mail.entity.MailDomain;
import com.anvicorp.api.mail.entity.MailRole;
import com.anvicorp.api.mail.repository.MailAccountRepository;
import com.anvicorp.api.mail.repository.MailDomainRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
 * silently falls through to raw SMTP → the intern's personal Gmail.</p>
 *
 * <h2>Boot-safety</h2>
 * <ol>
 *   <li>Opt-out kill-switch: {@code app.bootstrap.seed-role-mailboxes-enabled}
 *       (default {@code true}). If a future change makes this seeder
 *       hazardous, set env {@code SEED_ROLE_MAILBOXES_ENABLED=false} to
 *       skip it and unblock deploys without a code roll-back.</li>
 *   <li>Per-mailbox {@link TransactionTemplate} with
 *       {@code PROPAGATION_REQUIRES_NEW} — every insert runs in its own
 *       short tx so a schema mismatch on one row (e.g. a new NOT NULL
 *       column the entity hasn't caught up to yet) rolls back only that
 *       row and never poisons the boot's outer tx.</li>
 *   <li>Every DB call wrapped in per-row try/catch AND an outer
 *       try/catch, both logging at warn — a run failure is never fatal.</li>
 *   <li>Mirrors {@link MailAdminSeeder}'s field-set exactly (the working
 *       sibling that provisions the SUPER_ADMIN mailbox), plus explicit
 *       {@code quotaBytes} so the row is safe even if the DDL default
 *       drifts.</li>
 * </ol>
 *
 * <p><b>Ordering</b>: {@code @Order(8)} — after {@link MailAdminSeeder}
 * ({@code @Order(7)}) so the default domain is already present when
 * MailAdminSeeder is enabled; but this seeder ALSO resolve-or-seeds the
 * domain on its own so it works even when MailAdminSeeder is disabled
 * (its default state).</p>
 */
@Component
@Order(8)
@RequiredArgsConstructor
@Slf4j
public class MailRoleAccountSeeder implements CommandLineRunner {

    private static final String LOG_TAG = "[MailRoleAccountSeeder]";
    private static final String DEFAULT_DOMAIN = "anvicorp.com";
    /** 1 GiB — matches {@link MailAccount}'s @Builder.Default. Set
     *  explicitly here so the row is safe even if a DDL migration ever
     *  drops the column default. */
    private static final long DEFAULT_QUOTA_BYTES = 1_073_741_824L;

    // Literal local-parts — MUST STAY IN LOCK-STEP with
    // com.anvicorp.api.notification.NotificationSenderRoles constants
    // ("noreply" / "erm" / "trainer" / "evaluator" / "manager"). Not
    // imported from that class because those constants are package-
    // private in the notification package and would fail to compile
    // from this bootstrap package. If NotificationSenderRoles ever
    // makes them public, swap this list back to the constants.
    private static final List<RoleMailbox> ROLE_MAILBOXES = List.of(
            new RoleMailbox("noreply",           "Anvi (No Reply)"),
            new RoleMailbox("erm",               "Anvi ERM"),
            new RoleMailbox("trainer",           "Anvi Trainer"),
            new RoleMailbox("evaluator",         "Anvi Evaluator"),
            new RoleMailbox("manager",           "Anvi Manager"),
            new RoleMailbox("reporting-manager", "Anvi Reporting Manager"));

    private record RoleMailbox(String localPart, String displayName) {}

    private final MailDomainRepository domainRepository;
    private final MailAccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final PlatformTransactionManager transactionManager;

    @Value("${app.bootstrap.seed-role-mailboxes-enabled:true}")
    private boolean enabled;

    @Override
    public void run(String... args) {
        // Outer belt for anything the inner catches miss (e.g. an
        // unexpected Error thrown from static-init on lazy classes).
        // A boot-time NPE / LinkageError here would otherwise fail the
        // entire deploy — never worth it for a seeder.
        try {
            runInner();
        } catch (Throwable outer) {
            log.warn("{} outer failure (non-fatal, boot continues): {}",
                    LOG_TAG, outer.getMessage(), outer);
        }
    }

    private void runInner() {
        if (!enabled) {
            log.info("{} skipped — disabled via app.bootstrap.seed-role-mailboxes-enabled=false",
                    LOG_TAG);
            return;
        }

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        MailDomain domain;
        try {
            domain = resolveOrSeedDefaultDomain(tx);
        } catch (Throwable e) {
            log.warn("{} could not resolve/seed default domain '{}' — skipping (non-fatal): {}",
                    LOG_TAG, DEFAULT_DOMAIN, e.getMessage(), e);
            return;
        }
        if (domain == null) {
            log.warn("{} no default domain — skipping role mailbox seed", LOG_TAG);
            return;
        }

        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (RoleMailbox rm : ROLE_MAILBOXES) {
            try {
                boolean alreadyExists = tx.execute(status ->
                        accountRepository
                                .findByLocalPartAndDomain_Id(rm.localPart(), domain.getId())
                                .isPresent());
                if (Boolean.TRUE.equals(alreadyExists)) {
                    skipped++;
                    continue;
                }
                tx.executeWithoutResult(status -> {
                    // Random discarded password — these mailboxes are
                    // server-side FROM identities, not human logins. Must-
                    // change + require-change flags force rotation if a
                    // human ever tries to log in with a leaked secret.
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
                            .quotaBytes(DEFAULT_QUOTA_BYTES)
                            .build());
                });
                created++;
                log.info("{} provisioned role mailbox {}@{}",
                        LOG_TAG, rm.localPart(), domain.getName());
            } catch (Throwable perAccount) {
                failed++;
                log.warn("{} failed to seed {}@{}: {} — continuing (row rolled back, boot ok)",
                        LOG_TAG, rm.localPart(), domain.getName(),
                        perAccount.getMessage(), perAccount);
            }
        }
        log.info("{} done — created={} skipped_existing={} failed={} domain={}",
                LOG_TAG, created, skipped, failed, domain.getName());
    }

    /**
     * Resolve the default domain, seeding a new row when missing. Both
     * the read and the write run in their OWN {@code REQUIRES_NEW}
     * transactions so a schema mismatch on {@code mail_domains} rolls
     * back only itself.
     */
    private MailDomain resolveOrSeedDefaultDomain(TransactionTemplate tx) {
        Optional<MailDomain> byName = tx.execute(status ->
                domainRepository.findByName(DEFAULT_DOMAIN));
        if (byName != null && byName.isPresent()) return byName.get();
        try {
            MailDomain seeded = tx.execute(status ->
                    domainRepository.save(MailDomain.builder()
                            .name(DEFAULT_DOMAIN)
                            .displayName(DEFAULT_DOMAIN)
                            .active(true)
                            .build()));
            if (seeded != null) {
                log.warn("{} seeded default mail domain '{}'", LOG_TAG, DEFAULT_DOMAIN);
            }
            return seeded;
        } catch (Throwable e) {
            // Race with another instance seeding the same domain, or a
            // unique-constraint violation on rerun — re-read once so a
            // concurrent success is honored.
            log.info("{} default domain seed threw ({}) — re-reading",
                    LOG_TAG, e.getMessage());
            Optional<MailDomain> retry = tx.execute(status ->
                    domainRepository.findByName(DEFAULT_DOMAIN));
            return retry != null ? retry.orElse(null) : null;
        }
    }
}
