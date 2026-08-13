package com.anvicorp.api.erm;

import com.anvicorp.api.config.BrandConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Email Slice 3 — manager-hire-decision fold-ins.
 *
 * <p>Three ERM-facing inline bodies in
 * {@code InterviewEmailListener.notifyErmOfHireDecision} were migrated
 * to the CommunicationTemplate system: {@code MANAGER_HIRE_APPROVED},
 * {@code MANAGER_HIRE_HOLD}, {@code MANAGER_HIRE_DECLINED}. The
 * migration also collapses a latent double-send — the wire previously
 * passed {@code emailSent=false} to the dispatcher AND called
 * {@code emailProvider.sendRendered} explicitly, and because all three
 * MANAGER_HIRE_* strings live in the auto-email allow-list, the ERM
 * received two near-duplicate mails per hire decision. Slice 3 fixes
 * this by owning the email leg and passing {@code emailSent=true}.</p>
 *
 * <p>Every recipient (ERM only), every in-app dispatch, and every
 * decision branch is preserved exactly. Only the EMAIL body source
 * changes from inline construction to template render.</p>
 */
class EmailSlice3ManagerHireTest {

    private static final BrandConfig BRAND = new BrandConfig(
            "Acme Tech", "Acme Careers", "Acme Technologies LLC", "hello@acme.example");

    // ── Slice-3 seeds are registered ──────────────────────────────────

    @Test
    void slice3_new_seeds_are_registered() {
        assertSeedExists("MANAGER_HIRE_APPROVED");
        assertSeedExists("MANAGER_HIRE_HOLD");
        assertSeedExists("MANAGER_HIRE_DECLINED");
    }

    // ── Per-template render tests (call-site-shaped var maps) ─────────

    @Test
    void manager_hire_approved_renders_with_wiring_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("ermName", "Bob ERM");
        vars.put("internName", "Alice Applicant");
        vars.put("deepLink", "https://careers.acme.example/careers/erm/decision-center");
        var r = renderSeeded("MANAGER_HIRE_APPROVED", vars);
        assertRenderClean(r, "MANAGER_HIRE_APPROVED");
        assertTrue(r.subject().contains("Alice Applicant"),
                "subject must name the intern");
        assertTrue(r.body().contains("Bob ERM"),
                "body must greet the ERM by name");
        assertTrue(r.body().contains("Alice Applicant"));
        assertTrue(r.body().contains("SELECTED"),
                "body must state the resulting status");
        assertTrue(r.body().contains(
                "https://careers.acme.example/careers/erm/decision-center"),
                "body must carry the decision-center deep link");
    }

    @Test
    void manager_hire_hold_renders_with_note_inlined() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("ermName", "Bob");
        vars.put("internName", "Alice");
        vars.put("noteBlock", " Note: waiting on portfolio review.");
        vars.put("deepLink", "https://careers.acme.example/careers/erm/interviews/iv-1");
        var r = renderSeeded("MANAGER_HIRE_HOLD", vars);
        assertRenderClean(r, "MANAGER_HIRE_HOLD");
        assertTrue(r.subject().contains("Alice"));
        assertTrue(r.body().contains("ON HOLD"));
        assertTrue(r.body().contains("waiting on portfolio review"),
                "noteBlock content must be inlined");
        assertTrue(r.body().contains("iv-1"),
                "deepLink must resolve to the specific interview");
    }

    @Test
    void manager_hire_hold_renders_cleanly_when_note_is_empty() {
        // Manager parked the hire without a note — the wiring passes
        // an empty string for noteBlock. Template must render without
        // orphan " Note:" text or unresolved placeholders.
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("ermName", "Bob");
        vars.put("internName", "Alice");
        vars.put("noteBlock", "");
        vars.put("deepLink", "https://x/iv");
        var r = renderSeeded("MANAGER_HIRE_HOLD", vars);
        assertRenderClean(r, "MANAGER_HIRE_HOLD (no note)");
        assertFalse(r.body().contains("Note:"),
                "no 'Note:' text should appear when the noteBlock is empty");
    }

    @Test
    void manager_hire_declined_renders_with_wiring_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("ermName", "Bob");
        vars.put("internName", "Alice");
        vars.put("deepLink", "https://careers.acme.example/careers/erm/interviews/iv-2");
        var r = renderSeeded("MANAGER_HIRE_DECLINED", vars);
        assertRenderClean(r, "MANAGER_HIRE_DECLINED");
        assertTrue(r.subject().contains("Alice"));
        assertTrue(r.body().contains("Bob"));
        assertTrue(r.body().contains("declined"));
        assertTrue(r.body().contains("REJECTED"),
                "body must state the resulting status");
        assertTrue(r.body().contains("iv-2"),
                "deepLink must resolve to the specific interview");
    }

    // ── Rebrand-safety cross-check ────────────────────────────────────

    @Test
    void slice3_seeds_have_no_literal_anvi_after_brandify() {
        for (String key : List.of("MANAGER_HIRE_APPROVED", "MANAGER_HIRE_HOLD",
                "MANAGER_HIRE_DECLINED")) {
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

    // ── Grep-proof: listener no longer builds inline decision bodies ──

    /** After Slice 3, notifyErmOfHireDecision routes through
     *  templateService.render for all three decisions. The old inline
     *  strings ("A Manager approved the hire for", "ON HOLD — no final
     *  decision yet", "A Manager declined the hire for") now live ONLY
     *  in the rebrand-safe fallback that fires when the template row is
     *  absent — so each phrase appears at most once in the file. A
     *  regression that reintroduces per-decision inline construction at
     *  another call site would push the count above 1. */
    @Test
    void listener_carries_at_most_one_copy_of_each_decision_fallback_phrase() throws IOException {
        Path listener = locateListener();
        String source = Files.readString(listener, StandardCharsets.UTF_8);
        for (String phrase : List.of(
                "A Manager approved the hire for",
                "A Manager placed the hire for",
                "A Manager declined the hire for")) {
            int count = countMatches(source, phrase);
            assertTrue(count <= 1,
                    "Listener contains " + count + " copies of '" + phrase
                            + "' — a regression re-added inline body construction.");
        }
    }

    /** Grep-proof — no hardcoded brand strings leak into the listener
     *  wiring path. Slice 3 must remain rebrand-safe. */
    @Test
    void listener_carries_no_literal_brand_tokens() throws IOException {
        Path listener = locateListener();
        String source = Files.readString(listener, StandardCharsets.UTF_8);
        assertFalse(source.contains("\"Anvi Corp\""),
                "Listener must not embed literal \"Anvi Corp\" in any string literal.");
    }

    private static Path locateListener() {
        Path listener = Path.of("src/main/java/com/anvicorp/api/listener/"
                + "InterviewEmailListener.java");
        if (!Files.exists(listener)) {
            listener = Path.of("apps/backend/src/main/java/com/anvicorp/api/listener/"
                    + "InterviewEmailListener.java");
        }
        assertTrue(Files.exists(listener),
                "InterviewEmailListener.java must exist for grep-proof tests");
        return listener;
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

    private static int countMatches(String haystack, String needle) {
        int idx = 0, count = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
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
