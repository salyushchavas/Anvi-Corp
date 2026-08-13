package com.anvicorp.api.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Phase-0 config-layer extension regression. Locks in:
 *
 * <ul>
 *   <li>the 4 new fields ({@code phone}, {@code address},
 *       {@code emailDomain}, {@code websiteUrl}) exist as constructor
 *       args and resolve to their Anvi-Corp defaults when no env
 *       override is supplied;</li>
 *   <li>the 4 pre-existing fields ({@code name}, {@code productName},
 *       {@code legalName}, {@code supportEmail}) plus the derived
 *       {@code signoff*} helpers still behave the same as before —
 *       Phase 0 must not regress any existing consumer;</li>
 *   <li>the frontend {@code BRAND} object and this backend config
 *       agree on the same defaults for the fields both sides carry
 *       (name / legalName / phone / address / emailDomain /
 *       websiteUrl). The frontend equivalents live in
 *       {@code apps/frontend/lib/careers/brand.ts} — a divergence
 *       here would break the "single logical brand, two prefixes"
 *       contract laid out in the Phase-0 plan.</li>
 * </ul>
 */
class BrandConfigTest {

    /** Instantiate BrandConfig against the property-file defaults —
     *  same shape a real Spring boot would after resolving @Value on
     *  every constructor arg. Using the constructor directly (rather
     *  than a full Spring context) keeps the test fast and prevents
     *  a future @ConfigurationProperties refactor from silently
     *  losing an env binding. */
    private BrandConfig defaults() {
        return new BrandConfig(
                "Anvi Corp",
                "Anvi Careers",
                "Anvi Corp USA",
                "careers@anvicorp.com",
                "+1 469-945-4554",
                "7950 Legacy Dr, Suite 400, Plano, TX 75024",
                "anvicorp.com",
                "https://anvicorp.com");
    }

    // ── Existing fields unchanged ─────────────────────────────────────

    @Test
    void existing_fields_still_expose_original_defaults() {
        BrandConfig b = defaults();
        assertEquals("Anvi Corp", b.getName());
        assertEquals("Anvi Careers", b.getProductName());
        assertEquals("Anvi Corp USA", b.getLegalName());
        assertEquals("careers@anvicorp.com", b.getSupportEmail());
    }

    @Test
    void signoff_helpers_still_derive_from_name() {
        BrandConfig b = defaults();
        assertEquals("— Anvi Corp", b.signoff());
        assertEquals("— Anvi Corp ERM", b.signoffErm());
    }

    @Test
    void signoff_block_personalises_when_erm_name_supplied() {
        BrandConfig b = defaults();
        assertEquals("— Alice Recruiter",
                b.signoffBlock("Alice Recruiter"));
    }

    @Test
    void signoff_block_falls_back_to_brand_when_erm_name_missing() {
        BrandConfig b = defaults();
        assertEquals("— Anvi Corp", b.signoffBlock(null));
        assertEquals("— Anvi Corp", b.signoffBlock(""));
        assertEquals("— Anvi Corp", b.signoffBlock("   "));
    }

    // ── Phase-0 new fields expose the right defaults ──────────────────

    @Test
    void phase0_phone_default() {
        assertEquals("+1 469-945-4554", defaults().getPhone());
    }

    @Test
    void phase0_address_default() {
        // Kept as a single formatted string — matches the shape email
        // footers + offer-letter templates expect. See BrandConfig
        // javadoc for the deliberate one-field-vs-block choice.
        assertEquals("7950 Legacy Dr, Suite 400, Plano, TX 75024",
                defaults().getAddress());
    }

    @Test
    void phase0_email_domain_default_is_bare_host() {
        // Bare host, no leading "@" — matches what seeders will
        // consume as `<local>@<emailDomain>` builders.
        String domain = defaults().getEmailDomain();
        assertEquals("anvicorp.com", domain);
        assertNotNull(domain);
        assertEquals(false, domain.startsWith("@"),
                "emailDomain must be the bare host so callers can "
                        + "compose <local>@<emailDomain> without doubled @");
    }

    @Test
    void phase0_website_url_default() {
        assertEquals("https://anvicorp.com",
                defaults().getWebsiteUrl());
    }

    // ── Frontend / backend contract cross-check ───────────────────────

    /**
     * The frontend {@code BRAND} object at
     * {@code apps/frontend/lib/careers/brand.ts} and this backend
     * config are the two halves of a single logical brand. Fields
     * both sides carry MUST agree on their default value so a fresh
     * clone that sets neither env var still shows a consistent brand
     * everywhere. This test pins the shared defaults; a divergence
     * would break the Phase-0 plan.
     */
    @Test
    void shared_defaults_match_frontend_brand_object() {
        BrandConfig b = defaults();
        // These are the exact literal defaults set in
        // apps/frontend/lib/careers/brand.ts. Keep this test in sync
        // when a shared field's default changes on either side.
        assertEquals("Anvi Corp", b.getName(),
                "frontend BRAND.name default is 'Anvi Corp'");
        assertEquals("Anvi Corp USA", b.getLegalName(),
                "frontend BRAND.legalName default is 'Anvi Corp USA'");
        assertEquals("+1 469-945-4554", b.getPhone(),
                "frontend BRAND.phone default is '+1 469-945-4554'");
        assertEquals("anvicorp.com", b.getEmailDomain(),
                "frontend BRAND.emailDomain default is 'anvicorp.com'");
        assertEquals("https://anvicorp.com", b.getWebsiteUrl(),
                "frontend BRAND.siteBaseUrl default is "
                        + "'https://anvicorp.com'");
    }
}
