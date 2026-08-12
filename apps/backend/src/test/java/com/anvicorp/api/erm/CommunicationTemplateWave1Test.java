package com.anvicorp.api.erm;

import com.anvicorp.api.config.BrandConfig;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave-1 email-refinement regressions. Covers:
 *
 * <ul>
 *   <li>{@link BrandConfig#signoffBlock(String)} renders both the ERM-
 *       personalised shape and the brand-fallback shape.</li>
 *   <li>{@link CommunicationTemplateSeeder}'s reflection-invoked
 *       {@code rewrite} pass migrates literal {@code "Anvi Corp"} and
 *       the three legacy signoff patterns to their placeholder form.</li>
 *   <li>A rendered template resolves the auto-injected
 *       {@code {{brandName}}} placeholder from BrandConfig with no literal
 *       company name leaking through.</li>
 *   <li>{@code {{signoffBlock}}} in a template renders both shapes.</li>
 *   <li>The two new Wave-1 seeds (APPLICATION_RECEIVED, OFFER_ACCEPTED)
 *       exist in the seed list AND render with their variables.</li>
 *   <li>Grep-style assertion: after brandify, the seed source produces NO
 *       literal {@code "Anvi Corp"} or {@code "Anvi Corp ERM"} in the
 *       rewritten output for any seed (the invariant the seeder must
 *       preserve to keep the DB white-label-ready).</li>
 * </ul>
 *
 * <p>Zero-Spring test: instantiates {@link BrandConfig} +
 * {@link CommunicationTemplateSeeder} directly and calls the {@code
 * rewrite} pass via reflection to avoid needing the repository. Runs in
 * milliseconds.</p>
 */
class CommunicationTemplateWave1Test {

    private static final BrandConfig BRAND = new BrandConfig(
            "Acme Tech", "Acme Careers", "Acme Technologies LLC", "hello@acme.example");

    // ── BrandConfig.signoffBlock ──────────────────────────────────────

    @Test
    void signoffBlock_personalises_when_ermName_present() {
        assertEquals("— Alice Applicant", BRAND.signoffBlock("Alice Applicant"));
    }

    @Test
    void signoffBlock_falls_back_to_brand_when_ermName_null_or_blank() {
        assertEquals("— Acme Tech", BRAND.signoffBlock(null));
        assertEquals("— Acme Tech", BRAND.signoffBlock(""));
        assertEquals("— Acme Tech", BRAND.signoffBlock("   "));
    }

    @Test
    void signoffBlock_trims_supplied_ermName() {
        assertEquals("— Alice Applicant", BRAND.signoffBlock("  Alice Applicant  "));
    }

    // ── Seeder brandify() rewrite ──────────────────────────────────────

    @Test
    void brandify_rewrites_signoff_variants_to_signoffBlock_placeholder() {
        assertEquals("{{signoffBlock}}", rewrite("— Anvi Corp ERM"));
        assertEquals("{{signoffBlock}}", rewrite("— Anvi Corp"));
        assertEquals("{{signoffBlock}}", rewrite("— {{ermName}}"));
        assertEquals("{{signoffBlock}}", rewrite("— Skyzen ERM"));
    }

    @Test
    void brandify_rewrites_inline_brand_and_erm_references() {
        assertEquals(
                "Your {{brandName}} interview is scheduled",
                rewrite("Your Anvi Corp interview is scheduled"));
        assertEquals(
                "Please contact {{brandName}} ERM to reschedule",
                rewrite("Please contact Anvi Corp ERM to reschedule"));
    }

    @Test
    void brandify_leaves_placeholder_free_text_untouched() {
        assertEquals("Hello {{firstName}},", rewrite("Hello {{firstName}},"));
        assertEquals(null, rewrite(null));
        assertEquals("", rewrite(""));
    }

    /** Grep-style — every persisted subject and body across the SEEDS
     *  list must contain ZERO literal "Anvi Corp" or "Anvi Corp ERM"
     *  after brandify. Prevents a regression where a future edit adds
     *  a new template with hardcoded brand tokens. */
    @Test
    void seed_source_after_brandify_has_no_literal_anvi_corp() {
        Object seeder = newSeeder();
        for (CommunicationTemplateSeeder.Seed raw : allSeeds()) {
            CommunicationTemplateSeeder.Seed b = brandifyReflect(seeder, raw);
            assertFalse(containsAnviCorp(b.subject()),
                    "seed " + raw.key() + " subject still has literal Anvi Corp after brandify: "
                            + b.subject());
            assertFalse(containsAnviCorp(b.body()),
                    "seed " + raw.key() + " body still has literal Anvi Corp after brandify: "
                            + b.body());
        }
    }

    // ── CommunicationTemplateService render() auto-injection ──────────

    @Test
    void render_auto_injects_brandName_and_signoffBlock_for_erm_personalised_template() {
        var svc = new CommunicationTemplateService(new StubRepo(Map.of(
                "APPLICATION_REJECT|EMAIL",
                new StubTemplate("APPLICATION_REJECT", "EMAIL",
                        "Update on your {{brandName}} application",
                        "Hello {{firstName}},\n\n"
                                + "Thanks for applying at {{brandName}}. Contact {{supportEmail}}."
                                + "\n\n{{signoffBlock}}"))),
                BRAND);
        Map<String, Object> vars = new HashMap<>();
        vars.put("firstName", "Alice");
        vars.put("ermName", "Bob Recruiter");
        Optional<CommunicationTemplateService.Rendered> r =
                svc.render("APPLICATION_REJECT", "EMAIL", vars);
        assertTrue(r.isPresent(), "template should render");
        assertEquals("Update on your Acme Tech application", r.get().subject());
        // Body has personalised signoff because ermName was supplied.
        assertTrue(r.get().body().contains("Thanks for applying at Acme Tech."),
                "brandName should have been injected");
        assertTrue(r.get().body().contains("Contact hello@acme.example."),
                "supportEmail should have been injected");
        assertTrue(r.get().body().endsWith("— Bob Recruiter"),
                "signoffBlock should personalise when ermName is supplied");
        assertFalse(r.get().body().contains("Anvi"),
                "no literal Anvi should appear in rendered output");
    }

    @Test
    void render_signoffBlock_falls_back_to_brand_when_ermName_absent() {
        var svc = new CommunicationTemplateService(new StubRepo(Map.of(
                "K|EMAIL",
                new StubTemplate("K", "EMAIL", "S", "body\n\n{{signoffBlock}}"))),
                BRAND);
        Optional<CommunicationTemplateService.Rendered> r =
                svc.render("K", "EMAIL", Map.of());
        assertTrue(r.isPresent());
        assertTrue(r.get().body().endsWith("— Acme Tech"),
                "signoffBlock should fall back to brand line when no ermName");
    }

    @Test
    void render_returns_empty_when_template_absent() {
        var svc = new CommunicationTemplateService(new StubRepo(Map.of()), BRAND);
        assertTrue(svc.render("NOPE", "EMAIL", Map.of()).isEmpty());
    }

    // ── New Wave-1 seeds — APPLICATION_RECEIVED + OFFER_ACCEPTED ──────

    @Test
    void new_wave1_seeds_are_registered_in_seeds_list() {
        boolean foundAppReceived = allSeeds().stream()
                .anyMatch(s -> "APPLICATION_RECEIVED".equals(s.key()) && "EMAIL".equals(s.channel()));
        boolean foundOfferAccepted = allSeeds().stream()
                .anyMatch(s -> "OFFER_ACCEPTED".equals(s.key()) && "EMAIL".equals(s.channel()));
        assertTrue(foundAppReceived, "APPLICATION_RECEIVED seed must be in the list");
        assertTrue(foundOfferAccepted, "OFFER_ACCEPTED seed must be in the list");
    }

    @Test
    void application_received_seed_renders_with_declared_vars() {
        CommunicationTemplateSeeder.Seed raw = allSeeds().stream()
                .filter(s -> "APPLICATION_RECEIVED".equals(s.key()))
                .findFirst().orElseThrow();
        CommunicationTemplateSeeder.Seed b = brandifyReflect(newSeeder(), raw);
        var svc = new CommunicationTemplateService(new StubRepo(Map.of(
                "APPLICATION_RECEIVED|EMAIL",
                new StubTemplate("APPLICATION_RECEIVED", "EMAIL", b.subject(), b.body()))),
                BRAND);
        Optional<CommunicationTemplateService.Rendered> r = svc.render(
                "APPLICATION_RECEIVED", "EMAIL",
                Map.of("firstName", "Alice", "jobTitle", "Software Engineer"));
        assertTrue(r.isPresent());
        assertTrue(r.get().body().contains("Alice"));
        assertTrue(r.get().body().contains("Software Engineer"));
        assertTrue(r.get().body().contains("Acme Tech"),
                "brandName should be injected");
        assertTrue(r.get().body().contains("hello@acme.example"),
                "supportEmail should be injected");
        assertFalse(r.get().body().contains("Anvi"));
    }

    @Test
    void offer_accepted_seed_renders_with_declared_vars() {
        CommunicationTemplateSeeder.Seed raw = allSeeds().stream()
                .filter(s -> "OFFER_ACCEPTED".equals(s.key()))
                .findFirst().orElseThrow();
        CommunicationTemplateSeeder.Seed b = brandifyReflect(newSeeder(), raw);
        var svc = new CommunicationTemplateService(new StubRepo(Map.of(
                "OFFER_ACCEPTED|EMAIL",
                new StubTemplate("OFFER_ACCEPTED", "EMAIL", b.subject(), b.body()))),
                BRAND);
        Optional<CommunicationTemplateService.Rendered> r = svc.render(
                "OFFER_ACCEPTED", "EMAIL",
                Map.of("firstName", "Alice",
                        "jobTitle", "Software Engineer",
                        "tentativeStartDate", "2026-09-01"));
        assertTrue(r.isPresent());
        assertTrue(r.get().body().contains("Alice"));
        assertTrue(r.get().body().contains("Software Engineer"));
        assertTrue(r.get().body().contains("2026-09-01"));
        assertTrue(r.get().body().contains("Acme Technologies LLC"),
                "brandLegalEntity should be injected");
        assertFalse(r.get().body().contains("Anvi"));
    }

    // ── helpers ────────────────────────────────────────────────────────

    /** Invoke the seeder's package-private {@code rewrite} via reflection
     *  so tests exercise the exact production rewrite pass. */
    private static String rewrite(String in) {
        try {
            Object seeder = newSeeder();
            var m = CommunicationTemplateSeeder.class.getDeclaredMethod("rewrite", String.class);
            m.setAccessible(true);
            return (String) m.invoke(seeder, in);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static CommunicationTemplateSeeder.Seed brandifyReflect(
            Object seeder, CommunicationTemplateSeeder.Seed raw) {
        try {
            var m = CommunicationTemplateSeeder.class.getDeclaredMethod(
                    "brandify", CommunicationTemplateSeeder.Seed.class);
            m.setAccessible(true);
            return (CommunicationTemplateSeeder.Seed) m.invoke(seeder, raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Direct read of the private static SEEDS list via reflection.
     *  Kept read-only — no test mutates the list. */
    @SuppressWarnings("unchecked")
    private static List<CommunicationTemplateSeeder.Seed> allSeeds() {
        try {
            var f = CommunicationTemplateSeeder.class.getDeclaredField("SEEDS");
            f.setAccessible(true);
            return (List<CommunicationTemplateSeeder.Seed>) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Object newSeeder() {
        // Repository is unused by the pure rewrite/brandify paths; pass a
        // null-safe stub via reflection since the constructor is Lombok-
        // generated from @RequiredArgsConstructor.
        try {
            var ctor = CommunicationTemplateSeeder.class.getDeclaredConstructor(
                    CommunicationTemplateRepository.class, BrandConfig.class);
            return ctor.newInstance(null, BRAND);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean containsAnviCorp(String s) {
        if (s == null) return false;
        return s.contains("Anvi Corp");
    }

    // ── Test doubles for CommunicationTemplateService render() ────────

    /**
     * Minimal in-memory repo — implements the two methods
     * {@link CommunicationTemplateService} actually calls in the render
     * path. Everything else throws so an accidental broader usage
     * surfaces loudly.
     */
    private static final class StubRepo implements CommunicationTemplateRepository {
        private final Map<String, CommunicationTemplate> byKeyChannel;

        StubRepo(Map<String, CommunicationTemplate> byKeyChannel) {
            this.byKeyChannel = byKeyChannel;
        }

        @Override
        public Optional<CommunicationTemplate> findByKeyAndChannel(String key, String channel) {
            return Optional.ofNullable(byKeyChannel.get(key + "|" + channel));
        }

        @Override
        public boolean existsByKeyAndChannel(String key, String channel) {
            return byKeyChannel.containsKey(key + "|" + channel);
        }

        @Override
        public List<CommunicationTemplate> findByActiveTrueOrderByKeyAsc() {
            throw new UnsupportedOperationException("not used in these tests");
        }

        // Default JpaRepository methods are irrelevant here; the interface
        // extends JpaRepository so unimplemented defaults would kick in.
        // These stubs cover the two methods CommunicationTemplateService
        // uses; delegate everything else to Spring-Data's defaults which
        // aren't exercised in the pure-Java test path.

        @Override public <S extends CommunicationTemplate> S save(S entity) { return entity; }
        @Override public <S extends CommunicationTemplate> List<S> saveAll(Iterable<S> entities) { return List.of(); }
        @Override public Optional<CommunicationTemplate> findById(java.util.UUID id) { return Optional.empty(); }
        @Override public boolean existsById(java.util.UUID id) { return false; }
        @Override public List<CommunicationTemplate> findAll() { return List.of(); }
        @Override public List<CommunicationTemplate> findAllById(Iterable<java.util.UUID> ids) { return List.of(); }
        @Override public long count() { return 0; }
        @Override public void deleteById(java.util.UUID id) {}
        @Override public void delete(CommunicationTemplate entity) {}
        @Override public void deleteAllById(Iterable<? extends java.util.UUID> ids) {}
        @Override public void deleteAll(Iterable<? extends CommunicationTemplate> entities) {}
        @Override public void deleteAll() {}
        @Override public List<CommunicationTemplate> findAll(org.springframework.data.domain.Sort sort) { return List.of(); }
        @Override public org.springframework.data.domain.Page<CommunicationTemplate> findAll(
                org.springframework.data.domain.Pageable pageable) {
            return org.springframework.data.domain.Page.empty();
        }
        @Override public void flush() {}
        @Override public <S extends CommunicationTemplate> S saveAndFlush(S entity) { return entity; }
        @Override public <S extends CommunicationTemplate> List<S> saveAllAndFlush(Iterable<S> entities) { return List.of(); }
        @Override public void deleteAllInBatch(Iterable<CommunicationTemplate> entities) {}
        @Override public void deleteAllByIdInBatch(Iterable<java.util.UUID> ids) {}
        @Override public void deleteAllInBatch() {}
        @Override public CommunicationTemplate getOne(java.util.UUID id) { return null; }
        @Override public CommunicationTemplate getById(java.util.UUID id) { return null; }
        @Override public CommunicationTemplate getReferenceById(java.util.UUID id) { return null; }
        @Override public <S extends CommunicationTemplate> Optional<S> findOne(
                org.springframework.data.domain.Example<S> example) { return Optional.empty(); }
        @Override public <S extends CommunicationTemplate> List<S> findAll(
                org.springframework.data.domain.Example<S> example) { return List.of(); }
        @Override public <S extends CommunicationTemplate> List<S> findAll(
                org.springframework.data.domain.Example<S> example,
                org.springframework.data.domain.Sort sort) { return List.of(); }
        @Override public <S extends CommunicationTemplate> org.springframework.data.domain.Page<S> findAll(
                org.springframework.data.domain.Example<S> example,
                org.springframework.data.domain.Pageable pageable) {
            return org.springframework.data.domain.Page.empty();
        }
        @Override public <S extends CommunicationTemplate> long count(
                org.springframework.data.domain.Example<S> example) { return 0; }
        @Override public <S extends CommunicationTemplate> boolean exists(
                org.springframework.data.domain.Example<S> example) { return false; }
        @Override public <S extends CommunicationTemplate, R> R findBy(
                org.springframework.data.domain.Example<S> example,
                java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> fn) {
            throw new UnsupportedOperationException();
        }
    }

    /** Minimal template stand-in matching the entity fields the service reads. */
    private static class StubTemplate extends CommunicationTemplate {
        StubTemplate(String key, String channel, String subject, String body) {
            setKey(key);
            setChannel(channel);
            setSubjectTemplate(subject);
            setBodyTemplate(body);
        }
    }
}
