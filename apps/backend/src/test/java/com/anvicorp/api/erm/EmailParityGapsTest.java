package com.anvicorp.api.erm;

import com.anvicorp.api.config.BrandConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Definitive email-parity gap fills. Two true gaps identified by the
 * reconciliation pass:
 *
 * <ol>
 *   <li>{@code WORK_AUTH_EXPIRING_ERM} — ERM-facing counterpart for the
 *       work-auth-expiring alert (intern leg was already covered via
 *       {@code WORK_AUTH_EXPIRING}; the ERM CC leg only wrote an in-app
 *       row because the event is not in {@code EMAIL_ENABLED_EVENT_TYPES}
 *       and the auto-hook can't carry the real context).</li>
 *   <li>{@code IDMS_DOC_RETURNED} — intern-facing corrections-required
 *       alert for {@code DocumentInstanceService.returnForCorrections}
 *       (event is IDMS-native and not in the allow-list, no explicit
 *       send existed).</li>
 * </ol>
 *
 * <p>Each test drives the seeded template through the real render path
 * with the exact var set the caller supplies, and asserts: the seed
 * exists, the render succeeds, no unresolved {@code {{placeholder}}}
 * survives in either subject or body, no literal {@code Anvi} leaks
 * (brand auto-injection worked), and every caller-supplied value is
 * visible.</p>
 */
class EmailParityGapsTest {

    private static final BrandConfig BRAND = new BrandConfig(
            "Acme Tech", "Acme Careers", "Acme Technologies LLC", "hello@acme.example");

    // ── Seeds are registered ──────────────────────────────────────────

    @Test
    void parity_gap_seeds_are_registered() {
        assertSeedExists("WORK_AUTH_EXPIRING_ERM");
        assertSeedExists("IDMS_DOC_RETURNED");
    }

    // ── GAP 1: WORK_AUTH_EXPIRING_ERM ─────────────────────────────────

    @Test
    void work_auth_expiring_erm_renders_with_full_context() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("ermName", "Bob ERM");
        vars.put("internName", "Alice Intern");
        vars.put("workAuthType", "OPT");
        vars.put("expirationDate", "2026-11-30");
        vars.put("daysUntilExpiration", 30);
        vars.put("deepLink", "/careers/erm/compliance/12345");
        var r = renderSeeded("WORK_AUTH_EXPIRING_ERM", vars);
        assertRenderClean(r, "WORK_AUTH_EXPIRING_ERM");
        assertTrue(r.subject().contains("Alice Intern"),
                "subject must name the intern");
        assertTrue(r.subject().contains("OPT"),
                "subject must name the work-auth type");
        assertTrue(r.subject().contains("30"),
                "subject must show days remaining");
        assertTrue(r.body().contains("Bob ERM"),
                "body must greet the ERM by name");
        assertTrue(r.body().contains("Alice Intern"),
                "body must identify the intern");
        assertTrue(r.body().contains("OPT"),
                "body must show the work-auth type");
        assertTrue(r.body().contains("2026-11-30"),
                "body must show the expiry date");
        assertTrue(r.body().contains("/careers/erm/compliance/12345"),
                "body must carry the deep link");
    }

    @Test
    void work_auth_expiring_erm_supports_different_authtypes() {
        // Every visa-type string the WorkAuthExpiringEvent can carry
        // should render without complaint (the template treats
        // workAuthType as opaque text).
        for (String authType : List.of("CPT", "OPT", "H-1B", "STEM OPT", "L-1")) {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("ermName", "Bob");
            vars.put("internName", "Alice");
            vars.put("workAuthType", authType);
            vars.put("expirationDate", "2026-12-01");
            vars.put("daysUntilExpiration", 14);
            vars.put("deepLink", "/x");
            var r = renderSeeded("WORK_AUTH_EXPIRING_ERM", vars);
            assertRenderClean(r, "WORK_AUTH_EXPIRING_ERM (" + authType + ")");
            assertTrue(r.body().contains(authType),
                    "body must carry authType " + authType);
        }
    }

    // ── GAP 2: IDMS_DOC_RETURNED ──────────────────────────────────────

    @Test
    void idms_doc_returned_renders_with_reason_and_comments() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("ermName", "Bob ERM");
        vars.put("templateTitle", "Direct Deposit Form");
        vars.put("reasonBlock", "\n\nReason: missing signature");
        vars.put("commentsBlock", "\nERM comments: page 2 is blank");
        vars.put("deepLink",
                "https://careers.acme.example/careers/intern/agreements/e1");
        var r = renderSeeded("IDMS_DOC_RETURNED", vars);
        assertRenderClean(r, "IDMS_DOC_RETURNED");
        assertTrue(r.subject().contains("Direct Deposit Form"),
                "subject must name the document");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("Bob ERM"));
        assertTrue(r.body().contains("Direct Deposit Form"));
        assertTrue(r.body().contains("missing signature"),
                "reasonBlock must be inlined");
        assertTrue(r.body().contains("page 2 is blank"),
                "commentsBlock must be inlined");
        assertTrue(r.body().contains(
                "https://careers.acme.example/careers/intern/agreements/e1"));
    }

    @Test
    void idms_doc_returned_renders_when_reason_and_comments_are_empty() {
        // ERM returned the document without supplying a reasonCode or
        // comments — vars pass empty strings, the template must render
        // without leaving raw {{placeholders}} or awkward filler text.
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("ermName", "Bob ERM");
        vars.put("templateTitle", "Direct Deposit Form");
        vars.put("reasonBlock", "");
        vars.put("commentsBlock", "");
        vars.put("deepLink", "/x");
        var r = renderSeeded("IDMS_DOC_RETURNED", vars);
        assertRenderClean(r, "IDMS_DOC_RETURNED (empty reason+comments)");
        assertFalse(r.body().contains("Reason:"),
                "no 'Reason:' label should appear when the reason block is empty");
        assertFalse(r.body().contains("ERM comments:"),
                "no 'ERM comments:' label should appear when comments block is empty");
    }

    // ── Rebrand-safety cross-check ────────────────────────────────────

    @Test
    void neither_seed_carries_literal_brand_tokens_post_brandify() {
        // The seeder's brandify() rewrites "Anvi Corp" and the four
        // signoff patterns into placeholders. This test confirms both
        // new seeds go through the rewrite cleanly — no literal "Anvi"
        // survives, which is the invariant that keeps every deploy
        // white-label-ready.
        for (String key : List.of("WORK_AUTH_EXPIRING_ERM", "IDMS_DOC_RETURNED")) {
            CommunicationTemplateSeeder.Seed raw = allSeeds().stream()
                    .filter(s -> key.equals(s.key()) && "EMAIL".equals(s.channel()))
                    .findFirst()
                    .orElseThrow();
            CommunicationTemplateSeeder.Seed b = brandifyReflect(newSeeder(), raw);
            assertFalse(b.subject().contains("Anvi"),
                    key + " subject leaks literal Anvi after brandify: " + b.subject());
            assertFalse(b.body().contains("Anvi"),
                    key + " body leaks literal Anvi after brandify: " + b.body());
        }
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static void assertSeedExists(String key) {
        boolean found = allSeeds().stream()
                .anyMatch(s -> key.equals(s.key()) && "EMAIL".equals(s.channel()));
        assertTrue(found, key + " EMAIL seed must be registered in CommunicationTemplateSeeder");
    }

    private static CommunicationTemplateService.Rendered renderSeeded(
            String key, Map<String, Object> vars) {
        CommunicationTemplateSeeder.Seed raw = allSeeds().stream()
                .filter(s -> key.equals(s.key()) && "EMAIL".equals(s.channel()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "seed " + key + " missing from CommunicationTemplateSeeder"));
        CommunicationTemplateSeeder.Seed b = brandifyReflect(newSeeder(), raw);
        var svc = new CommunicationTemplateService(new StubRepo(Map.of(
                key + "|EMAIL",
                new StubTemplate(key, "EMAIL", b.subject(), b.body()))),
                BRAND);
        Optional<CommunicationTemplateService.Rendered> r =
                svc.render(key, "EMAIL", vars);
        assertTrue(r.isPresent(), key + " render should return a body");
        return r.get();
    }

    private static void assertRenderClean(
            CommunicationTemplateService.Rendered r, String key) {
        assertNotNull(r.subject(), key + " subject should be set");
        assertNotNull(r.body(), key + " body should be set");
        assertFalse(hasUnresolvedPlaceholder(r.subject()),
                key + " subject has unresolved {{placeholder}}: " + r.subject());
        assertFalse(hasUnresolvedPlaceholder(r.body()),
                key + " body has unresolved {{placeholder}}: " + r.body());
        assertFalse(r.subject().contains("Anvi"),
                key + " subject leaked literal Anvi (brand-var missing?): " + r.subject());
        assertFalse(r.body().contains("Anvi"),
                key + " body leaked literal Anvi (brand-var missing?): " + r.body());
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*[\\w.]+\\s*\\}\\}");

    private static boolean hasUnresolvedPlaceholder(String s) {
        return s != null && PLACEHOLDER.matcher(s).find();
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
        try {
            var ctor = CommunicationTemplateSeeder.class.getDeclaredConstructor(
                    CommunicationTemplateRepository.class, BrandConfig.class);
            return ctor.newInstance(null, BRAND);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── Test doubles ──────────────────────────────────────────────────

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

    private static class StubTemplate extends CommunicationTemplate {
        StubTemplate(String key, String channel, String subject, String body) {
            setKey(key);
            setChannel(channel);
            setSubjectTemplate(subject);
            setBodyTemplate(body);
        }
    }
}
