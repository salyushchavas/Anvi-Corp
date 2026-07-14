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

/**
 * Bootstrap seeder for the mail module — gated by {@code SEED_MAIL_ADMIN} and
 * idempotent. Ensures the configured domain (default {@code anvicorp.com})
 * exists and a SUPER_ADMIN mail account is present with a BCrypt-hashed password
 * and {@code must_change_password=true}. ddl-auto has already created the
 * {@code mail_*} tables before any {@code CommandLineRunner} executes. Never
 * crashes startup (try/catch, non-fatal).
 */
@Component
@Order(7)
@RequiredArgsConstructor
@Slf4j
public class MailAdminSeeder implements CommandLineRunner {

    private final MailDomainRepository domainRepository;
    private final MailAccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.webmail.seed.admin-enabled:false}")
    private boolean enabled;

    @Value("${app.webmail.seed.admin-email:}")
    private String adminEmail;

    @Value("${app.webmail.seed.admin-password:}")
    private String adminPassword;

    @Value("${app.webmail.seed.admin-domain:anvicorp.com}")
    private String adminDomain;

    @Override
    public void run(String... args) {
        try {
            if (!enabled) {
                return;
            }
            if (adminEmail == null || adminEmail.isBlank()
                    || adminPassword == null || adminPassword.isBlank()) {
                log.warn("MailAdminSeeder: SEED_MAIL_ADMIN=true but MAIL_ADMIN_EMAIL/MAIL_ADMIN_PASSWORD "
                        + "are not set — skipping mail super-admin seed");
                return;
            }

            String email = adminEmail.trim().toLowerCase();
            String localPart;
            String domainName;
            int at = email.indexOf('@');
            if (at > 0 && at < email.length() - 1) {
                localPart = email.substring(0, at);
                domainName = email.substring(at + 1);
            } else {
                localPart = email;
                domainName = adminDomain.trim().toLowerCase();
            }

            MailDomain domain = domainRepository.findByName(domainName).orElseGet(() -> {
                MailDomain created = domainRepository.save(MailDomain.builder()
                        .name(domainName)
                        .displayName(domainName)
                        .active(true)
                        .build());
                log.warn("MailAdminSeeder: created mail domain '{}'", domainName);
                return created;
            });

            if (accountRepository.findByLocalPartAndDomain_Name(localPart, domainName).isPresent()) {
                // Already seeded — idempotent no-op.
                return;
            }

            accountRepository.save(MailAccount.builder()
                    .domain(domain)
                    .localPart(localPart)
                    .displayName("Mail Super Admin")
                    .passwordHash(passwordEncoder.encode(adminPassword))
                    .role(MailRole.SUPER_ADMIN)
                    .status(MailAccountStatus.ACTIVE)
                    .mustChangePassword(true)
                    .requireChangeOnFirstLogin(true)
                    .build());

            log.warn("MailAdminSeeder: created SUPER_ADMIN mail account {}@{} — "
                    + "must change password on first login. CHANGE IT IMMEDIATELY.", localPart, domainName);
        } catch (Exception e) {
            log.warn("MailAdminSeeder failed (non-fatal): {}", e.getMessage(), e);
        }
    }
}
