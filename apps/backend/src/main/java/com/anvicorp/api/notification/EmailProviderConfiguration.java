package com.anvicorp.api.notification;

import com.anvicorp.api.mail.repository.MailAccountRepository;
import com.anvicorp.api.mail.repository.MailDomainRepository;
import com.anvicorp.api.mail.service.MailMessageService;
import com.anvicorp.api.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Wires exactly one {@link EmailProvider} into the context based on config:
 *
 * <ul>
 *   <li>If {@code spring.mail.host} AND {@code spring.mail.username} are
 *       non-blank → real {@link SmtpEmailProvider} (production / any env
 *       with real SMTP credentials), optionally wrapped by
 *       {@link BridgingEmailProvider} so notifications for
 *       {@code mailHandoverState=ACTIVATED} interns land in the internal
 *       mailbox FROM the acting staff mailbox (evaluator@/erm@/trainer@/
 *       manager@) via {@link MailMessageService#deliverInternalNotification}.
 *       Everyone else falls through to SMTP unchanged.</li>
 *   <li>Otherwise → {@link LogEmailProvider} that just logs at INFO. Lets
 *       dev / CI / freshly-provisioned environments boot cleanly without
 *       an SMTP account. The bridge is not needed in this mode.</li>
 * </ul>
 *
 * <p>The bridge activation flag ({@code app.mail.bridge.enabled}, default
 * true) exists so operators can flip it off in prod without a redeploy if
 * the internal-mail routing ever misbehaves — SMTP is the safe fallback.</p>
 */
@Configuration
@Slf4j
public class EmailProviderConfiguration {

    @Bean
    public EmailProvider emailProvider(
            @Value("${spring.mail.host:}") String host,
            @Value("${spring.mail.username:}") String username,
            @Value("${app.mail.from:noreply@anvicorp.com}") String mailFromAddress,
            @Value("${app.mail.from-name:Anvi Corp}") String mailFromName,
            @Value("${app.brand.logo-url:https://anvicorp.com/logo.png}") String logoUrl,
            @Value("${app.brand.url:https://anvicorp.com}") String brandUrl,
            @Value("${app.brand.name:Anvi Corp}") String brand,
            @Value("${app.brand.product:Anvi Careers}") String product,
            @Value("${app.mail.bridge.enabled:true}") boolean bridgeEnabled,
            org.springframework.beans.factory.ObjectProvider<JavaMailSender> mailSenderProvider,
            org.springframework.beans.factory.ObjectProvider<UserRepository> userRepositoryProvider,
            org.springframework.beans.factory.ObjectProvider<MailAccountRepository> mailAccountRepositoryProvider,
            org.springframework.beans.factory.ObjectProvider<MailDomainRepository> mailDomainRepositoryProvider,
            org.springframework.beans.factory.ObjectProvider<MailMessageService> mailMessageServiceProvider
    ) {
        boolean smtpConfigured = host != null && !host.isBlank()
                && username != null && !username.isBlank();

        if (!smtpConfigured) {
            log.warn("EmailProviderConfiguration: spring.mail.host/username not set — "
                    + "using LogEmailProvider. Set SMTP env vars for real sends.");
            return new LogEmailProvider();
        }

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.warn("EmailProviderConfiguration: spring-boot-starter-mail is on the "
                    + "classpath but JavaMailSender bean was not created — falling back "
                    + "to LogEmailProvider. Verify spring.mail.* config.");
            return new LogEmailProvider();
        }

        SmtpEmailProvider smtp = new SmtpEmailProvider(mailSender, mailFromAddress, mailFromName,
                logoUrl, brandUrl, brand, product);

        if (!bridgeEnabled) {
            log.info("EmailProviderConfiguration: SMTP configured (host={}, from={}) — "
                    + "bridge disabled via app.mail.bridge.enabled=false, using raw SmtpEmailProvider",
                    host, mailFromAddress);
            return smtp;
        }

        UserRepository users = userRepositoryProvider.getIfAvailable();
        MailAccountRepository accounts = mailAccountRepositoryProvider.getIfAvailable();
        MailDomainRepository domains = mailDomainRepositoryProvider.getIfAvailable();
        MailMessageService messages = mailMessageServiceProvider.getIfAvailable();
        if (users == null || accounts == null || domains == null || messages == null) {
            log.warn("EmailProviderConfiguration: mail-bridge dependencies unavailable "
                    + "(users={}, accounts={}, domains={}, messages={}) — using raw SmtpEmailProvider",
                    users != null, accounts != null, domains != null, messages != null);
            return smtp;
        }

        log.info("EmailProviderConfiguration: SMTP configured (host={}, from={}) — "
                + "wrapping with BridgingEmailProvider for staff→ACTIVATED-intern routing",
                host, mailFromAddress);
        return new BridgingEmailProvider(smtp, users, accounts, domains, messages);
    }
}
