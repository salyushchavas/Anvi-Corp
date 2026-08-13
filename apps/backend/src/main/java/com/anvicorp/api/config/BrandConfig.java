package com.anvicorp.api.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
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

    // ── Phase-0 config-layer extension ─────────────────────────────────
    // Fields the identity-centralization survey flagged as missing on
    // the backend side. Same @Value pattern, same BRAND_* env-var +
    // app.brand.* property namespace, Anvi-Corp defaults so nothing
    // changes for the current deploy until an operator overrides.
    // NOTE: no reference migration is done in this commit — the
    // batches that consume these come next (seeders currently
    // hardcode `@anvicorp.com`; email footers hardcode phone/address).

    /** Company phone (E.164 / US-formatted, for email footer + templates). */
    private final String phone;
    /**
     * Physical address as a single formatted string. Kept as one field
     * (not a block) because 100% of the current backend consumers are
     * email footers / offer-letter boilerplate that want a preformatted
     * line rather than a struct — matches the shape template render
     * vars expect.
     */
    private final String address;
    /**
     * Bare corporate mailbox suffix (no @). Consumed by the account
     * seeders — currently they hardcode "@anvicorp.com" inline; this
     * gives them a real config to read.
     */
    private final String emailDomain;
    /**
     * Absolute canonical URL for the marketing site. Distinct from
     * {@code app.frontend.base-url} which points at the careers /
     * dashboard host; these can differ (e.g. www.brand.com vs
     * app.brand.com). Both kept — {@code frontend.base-url} stays for
     * deep-link construction in listeners; this is for
     * template-visible / footer-visible marketing URLs.
     */
    private final String websiteUrl;

    // @Autowired on the 8-arg primary so Spring picks it deterministically
    // now that the class has a secondary test-convenience constructor —
    // without this, ctor auto-selection fails with "No default constructor
    // found" (Spring can't tell which of the two overloads to inject).
    @Autowired
    public BrandConfig(
            @Value("${app.brand.name:Anvi Corp}") String name,
            @Value("${app.brand.product-name:Anvi Careers}") String productName,
            @Value("${app.brand.legal-name:Anvi Corp USA}") String legalName,
            @Value("${app.brand.support-email:careers@anvicorp.com}") String supportEmail,
            @Value("${app.brand.phone:+1 469-945-4554}") String phone,
            @Value("${app.brand.address:7950 Legacy Dr, Suite 400, Plano, TX 75024}") String address,
            @Value("${app.brand.email-domain:anvicorp.com}") String emailDomain,
            @Value("${app.brand.website-url:https://anvicorp.com}") String websiteUrl
    ) {
        this.name = name;
        this.productName = productName;
        this.legalName = legalName;
        this.supportEmail = supportEmail;
        this.phone = phone;
        this.address = address;
        this.emailDomain = emailDomain;
        this.websiteUrl = websiteUrl;
    }

    /**
     * Convenience 4-arg constructor for tests that only care about the
     * brand-name / signoff surface and don't want to invent phone /
     * address values. Delegates to the full constructor with the
     * production Anvi-Corp defaults for the 4 Phase-0 fields — same
     * shape a plain {@code new BrandConfig("A","B","C","D")} call gave
     * before Phase-0 extended the constructor. Preserves compatibility
     * for {@link com.anvicorp.api.erm.CommunicationTemplateWave1Test},
     * the 5 Email-Slice tests, and any future test that constructs
     * BrandConfig directly without needing the new fields.
     */
    public BrandConfig(String name, String productName,
                       String legalName, String supportEmail) {
        this(name, productName, legalName, supportEmail,
                "+1 469-945-4554",
                "7950 Legacy Dr, Suite 400, Plano, TX 75024",
                "anvicorp.com",
                "https://anvicorp.com");
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

    /**
     * Wave-1 unified signoff. Personalises to the specific ERM when a
     * name is supplied (offer-family and any listener that already knows
     * who's sending); otherwise falls back to the brand-level line. Kept
     * as ONE shape rather than the three that used to co-exist
     * ({@code "— Anvi Corp ERM"} / {@code "— {{ermName}}"} /
     * {@code "— Anvi Corp"}) so every template signs off consistently.
     *
     * <p>Resolution rule: {@code ermName} present + non-blank →
     * {@code "— {ermName}"}. Otherwise → {@code "— {name}"} (the
     * brand-level line — same shape as {@link #signoff()}).</p>
     */
    public String signoffBlock(String ermName) {
        if (ermName != null && !ermName.isBlank()) {
            return "— " + ermName.trim();
        }
        return "— " + name;
    }
}
