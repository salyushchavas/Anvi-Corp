package com.anvicorp.api.notification;

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
 *       with real SMTP credentials).</li>
 *   <li>Otherwise → {@link LogEmailProvider} that just logs at INFO. Lets
 *       dev / CI / freshly-provisioned environments boot cleanly without
 *       an SMTP account.</li>
 * </ul>
 *
 * <p>NOTE: this is the SIMPLIFIED version — Skyzen's original also selected
 * {@code BridgingEmailProvider} to route certain careers→intern messages
 * through the internal mail module. Anvi's mail module is self-contained
 * and does not expose that bridge, so careers notifications go straight to
 * SMTP (or the log fallback). If a mail-bridge is added later, restore the
 * three-way selector here.</p>
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
            org.springframework.beans.factory.ObjectProvider<JavaMailSender> mailSenderProvider
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

        log.info("EmailProviderConfiguration: SMTP configured (host={}, from={}) — using SmtpEmailProvider",
                host, mailFromAddress);
        return new SmtpEmailProvider(mailSender, mailFromAddress, mailFromName,
                logoUrl, brandUrl, brand, product);
    }
}
