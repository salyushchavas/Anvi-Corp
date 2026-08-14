package com.anvicorp.api.bootstrap;

import com.anvicorp.api.config.BrandConfig;
import com.anvicorp.api.mail.entity.MailDomain;
import com.anvicorp.api.mail.repository.MailDomainRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Provisions the acting-staff mail_accounts the BridgingEmailProvider
 * relies on when routing an internal notification's FROM address.
 *
 * <p><b>Why this seeder exists</b>: {@code BridgingEmailProvider}'s
 * fifth guard resolves the sender by
 * {@code mailAccountRepository.findByLocalPartAndDomain_Id(senderLocalPart,
 * defaultDomain.id)}. The sender local-part comes from
 * {@link com.anvicorp.api.notification.NotificationSenderRoles} — one of
 * {@code noreply / erm / trainer / evaluator / manager}. If the
 * corresponding {@code mail_accounts} row doesn't exist, the guard
 * returns false and the notification silently falls through to raw SMTP.</p>
 *
 * <h2>Why raw JDBC (not the JPA repository)</h2>
 * An earlier iteration used {@code accountRepository.findByLocalPartAndDomain_Id}
 * for the existence check, and the boot log claimed
 * {@code created=0 skipped_existing=6} even though {@code SELECT ... FROM
 * mail_accounts JOIN mail_domains WHERE name='anvicorp.com'} did NOT
 * show the six role local-parts. The Spring Data derivation was
 * matching something the operator's SQL didn't. To eliminate that whole
 * class of ambiguity, this seeder now uses {@link JdbcTemplate} with
 * literal {@code WHERE local_part = ? AND domain_id = ?} predicates —
 * byte-identical to the operator's diagnostic SQL — for both the
 * existence check AND the insert. If the seeder logs "skipped" now, it
 * literally means the exact row is in the exact table under the exact
 * domain the bridge's G5 lookup would use.
 *
 * <h2>Domain resolution alignment</h2>
 * The bridge resolves the default domain via {@code mailDomainRepository
 * .findByName("anvicorp.com")}. This seeder does the same first, then
 * uses that domain's UUID for both existence checks and inserts. So
 * seeder and bridge always agree on which {@code mail_domains} row they
 * mean — no risk of the seeder creating rows under a duplicate/orphan
 * domain that the bridge never checks.
 *
 * <h2>Boot-safety</h2>
 * <ol>
 *   <li>Kill-switch: {@code app.bootstrap.seed-role-mailboxes-enabled}
 *       (default {@code true}).</li>
 *   <li>Per-mailbox {@link TransactionTemplate} with
 *       {@code PROPAGATION_REQUIRES_NEW} — one bad row rolls back
 *       only itself.</li>
 *   <li>Every DB call wrapped in per-row try/catch AND outer
 *       try/catch — never fatal.</li>
 *   <li>{@code run()} catches {@code Throwable} so a stray Error
 *       can't fail the deploy.</li>
 * </ol>
 */
@Component
@Order(8)
@RequiredArgsConstructor
@Slf4j
public class MailRoleAccountSeeder implements CommandLineRunner {

    private static final String LOG_TAG = "[MailRoleAccountSeeder]";
    /** 1 GiB — matches {@code MailAccount}'s @Builder.Default. Set
     *  explicitly here so the row is safe even if a DDL migration ever
     *  drops the column default. */
    private static final long DEFAULT_QUOTA_BYTES = 1_073_741_824L;

    // Local-parts + display-role suffixes — MUST STAY IN LOCK-STEP with
    // com.anvicorp.api.notification.NotificationSenderRoles constants
    // ("noreply" / "erm" / "trainer" / "evaluator" / "manager"). Not
    // imported from that class because those constants are package-
    // private in the notification package. The display-name prefix
    // (e.g. "Anvi") is derived at run() time from BrandConfig.getShortName()
    // so a clone renders "{Brand} ERM" etc. — for Anvi (shortName="Anvi")
    // the rendered display names are byte-identical to the previous
    // hardcoded values.
    private static final List<RoleMailbox> ROLE_MAILBOXES = List.of(
            new RoleMailbox("noreply",           "(No Reply)"),
            new RoleMailbox("erm",               "ERM"),
            new RoleMailbox("trainer",           "Trainer"),
            new RoleMailbox("evaluator",         "Evaluator"),
            new RoleMailbox("manager",           "Manager"),
            new RoleMailbox("reporting-manager", "Reporting Manager"));

    private record RoleMailbox(String localPart, String displayRoleSuffix) {}

    private final MailDomainRepository domainRepository;
    private final PasswordEncoder passwordEncoder;
    private final PlatformTransactionManager transactionManager;
    private final JdbcTemplate jdbcTemplate;
    private final BrandConfig brand;

    @Value("${app.bootstrap.seed-role-mailboxes-enabled:true}")
    private boolean enabled;

    @Override
    public void run(String... args) {
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

        String defaultDomain = brand.getEmailDomain();
        String shortName = brand.getShortName();
        MailDomain domain;
        try {
            domain = resolveOrSeedDefaultDomain(tx, defaultDomain);
        } catch (Throwable e) {
            log.warn("{} could not resolve/seed default domain '{}' — skipping (non-fatal): {}",
                    LOG_TAG, defaultDomain, e.getMessage(), e);
            return;
        }
        if (domain == null) {
            log.warn("{} no default domain — skipping role mailbox seed", LOG_TAG);
            return;
        }
        // The exact domain UUID both this seeder and BridgingEmailProvider's
        // G5 will use. Logged so a future "created=0 skipped_existing=N"
        // contradiction can be diagnosed at a glance.
        UUID domainId = domain.getId();
        log.info("{} using domain id={} name='{}' for existence check + insert",
                LOG_TAG, domainId, domain.getName());

        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (RoleMailbox rm : ROLE_MAILBOXES) {
            try {
                // Raw SQL — byte-identical to the operator's diagnostic
                // query. If this returns >0, the row REALLY exists under
                // this exact domain and the bridge's G5 will find it too.
                Integer existing = tx.execute(status -> jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM mail_accounts "
                                + "WHERE local_part = ? AND domain_id = ?",
                        Integer.class, rm.localPart(), domainId));
                int existingCount = existing == null ? 0 : existing;
                if (existingCount > 0) {
                    skipped++;
                    continue;
                }

                // Insert via raw JDBC too — same reason: what you see in
                // the seeder is what lands in the table. Column list
                // mirrors MailAccount's @Column names exactly; the schema's
                // defaults handle status/must_change columns even where
                // the entity's @Builder.Default doesn't apply.
                String displayName = shortName + " " + rm.displayRoleSuffix();
                tx.executeWithoutResult(status -> {
                    UUID newId = UUID.randomUUID();
                    String randomPassword = UUID.randomUUID().toString();
                    jdbcTemplate.update(
                            "INSERT INTO mail_accounts "
                                    + "(id, domain_id, local_part, display_name, password_hash, "
                                    + " role, status, must_change_password, "
                                    + " require_change_on_first_login, quota_bytes, created_at) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                            newId,
                            domainId,
                            rm.localPart(),
                            displayName,
                            passwordEncoder.encode(randomPassword),
                            "USER",
                            "ACTIVE",
                            true,
                            true,
                            DEFAULT_QUOTA_BYTES);
                });
                created++;
                log.info("{} provisioned role mailbox {}@{} (domain id={})",
                        LOG_TAG, rm.localPart(), domain.getName(), domainId);
            } catch (Throwable perAccount) {
                failed++;
                log.warn("{} failed to seed {}@{} (domain id={}): {} — continuing",
                        LOG_TAG, rm.localPart(), domain.getName(), domainId,
                        perAccount.getMessage(), perAccount);
            }
        }
        log.info("{} done — created={} skipped_existing={} failed={} domain={} (id={})",
                LOG_TAG, created, skipped, failed, domain.getName(), domainId);
    }

    /**
     * Resolve the default domain, seeding a new row when missing. Uses
     * the SAME entrypoint the bridge uses ({@code findByName}) so both
     * always resolve to the exact same UUID row.
     */
    private MailDomain resolveOrSeedDefaultDomain(TransactionTemplate tx, String defaultDomain) {
        Optional<MailDomain> byName = tx.execute(status ->
                domainRepository.findByName(defaultDomain));
        if (byName != null && byName.isPresent()) return byName.get();
        try {
            MailDomain seeded = tx.execute(status ->
                    domainRepository.save(MailDomain.builder()
                            .name(defaultDomain)
                            .displayName(defaultDomain)
                            .active(true)
                            .build()));
            if (seeded != null) {
                log.warn("{} seeded default mail domain '{}'", LOG_TAG, defaultDomain);
            }
            return seeded;
        } catch (Throwable e) {
            log.info("{} default domain seed threw ({}) — re-reading", LOG_TAG, e.getMessage());
            Optional<MailDomain> retry = tx.execute(status ->
                    domainRepository.findByName(defaultDomain));
            return retry != null ? retry.orElse(null) : null;
        }
    }
}
