package com.anvicorp.api.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for brand identity in body copy. Injected into
 * email-template builders, the activation invite, the offer letter PDF,
 * and any other place a brand name appears in user-facing text.
 *
 * <h2>Defaults</h2>
 * Skyzen. With no {@code BRAND_*} env vars set, every consumer renders
 * identically to today.
 *
 * <h2>Separation from MAIL_FROM_NAME</h2>
 * The {@code From:} header's personal name lives on
 * {@code EmailProviderConfiguration} ({@code MAIL_FROM_NAME}) — it's the
 * sender identity, not the body brand. They overlap by default but a
 * deployment can split them (e.g. brand="Acme Tech", from-name="Acme HR").
 */
@Component
@Getter
public class BrandConfig {

    /** Short brand name surfaced in email subjects + body. e.g. "Anvi Corp". */
    private final String name;
    /** "{name} Careers" idiomatic product name. e.g. "Anvi Careers". */
    private final String productName;
    /** Legal entity name for footers, contracts, offer letters. e.g. "Anvi Corp USA". */
    private final String legalName;
    /** mailto: support address surfaced in templates + UI. */
    private final String supportEmail;

    public BrandConfig(
            @Value("${app.brand.name:Anvi Corp}") String name,
            @Value("${app.brand.product-name:Anvi Careers}") String productName,
            @Value("${app.brand.legal-name:Anvi Corp USA}") String legalName,
            @Value("${app.brand.support-email:careers@anvicorp.com}") String supportEmail
    ) {
        this.name = name;
        this.productName = productName;
        this.legalName = legalName;
        this.supportEmail = supportEmail;
    }

    /**
     * Signature line for the tail of intern/employee emails —
     * {@code "— {name}"}. Previously every dispatcher hardcoded
     * {@code "— Skyzen"}; routing through here keeps a per-brand deploy
     * from having to touch every notification service.
     */
    public String signoff() {
        return "— " + name;
    }

    /**
     * ERM-suffixed signoff used by the pre-hire funnel emails (ERM is
     * the applicant-facing role from apply → offer). Same shape as
     * {@link #signoff()} but adds " ERM" so the copy stays true to
     * the original CommunicationTemplateSeeder tone.
     */
    public String signoffErm() {
        return "— " + name + " ERM";
    }
}
