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
 * Email Slice 6c — {@code OnboardingTrackerService} fold-ins
 * (Category C). Migrates the two remaining inline
 * {@code sendBrandedHtml} sites to template-first with a rebrand-safe
 * fallback that preserves the pre-migration copy:
 *
 * <ol>
 *   <li>{@code notifyTeam} (via {@code notifyOne} fan-out) →
 *       new {@code INTERN_ONBOARDING_ANNOUNCED} template — fires 3× per
 *       team-notify (trainer / evaluator / manager).</li>
 *   <li>{@code sendSignatureReminder} → new
 *       {@code OFFER_SIGN_REMINDER} template — ad-hoc offer-signature
 *       nudge from the tracker's Signature Reminder button.</li>
 * </ol>
 *
 * <p>Recipients (staff for notifyTeam; intern for
 * sendSignatureReminder), in-app dispatch, and timing are preserved
 * exactly. Both dispatch sites already pass {@code emailSent=true} and
 * neither event is in {@code EMAIL_ENABLED_EVENT_TYPES} — no double-
 * send risk. HTML-twin delivery is dropped in favour of
 * {@code sendRendered} (text) — same pattern every prior slice uses;
 * onboarding notifications don't need rich HTML.</p>
 */
class EmailSlice6cOnboardingTrackerTest {

    private static final BrandConfig BRAND = new BrandConfig(
            "Acme Tech", "Acme Careers", "Acme Technologies LLC", "hello@acme.example");

    // ── Slice-6c seeds are registered ─────────────────────────────────

    @Test
    void slice6c_seeds_are_registered() {
        assertSeedExists("INTERN_ONBOARDING_ANNOUNCED");
        assertSeedExists("OFFER_SIGN_REMINDER");
    }

    // ── INTERN_ONBOARDING_ANNOUNCED render tests ──────────────────────

    @Test
    void intern_onboarding_announced_renders_with_notify_team_vars() {
        // Var map matches OnboardingTrackerService.notifyOne exactly:
        // firstName (STAFF recipient), internName, deepLink.
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Bob Trainer");
        vars.put("internName", "Alice Applicant");
        vars.put("deepLink",
                "https://careers.acme.example/careers/trainer/active-interns");
        var r = renderSeeded("INTERN_ONBOARDING_ANNOUNCED", vars);
        assertRenderClean(r, "INTERN_ONBOARDING_ANNOUNCED");
        assertTrue(r.subject().contains("Alice Applicant"),
                "subject must name the intern");
        assertTrue(r.body().contains("Bob Trainer"),
                "body must greet the staff recipient by name");
        assertTrue(r.body().contains("Alice Applicant"));
        assertTrue(r.body().contains(
                "https://careers.acme.example/careers/trainer/active-interns"));
        // brandName auto-injects "Acme Tech" via the render layer.
        assertTrue(r.body().contains("Acme Tech"));
    }

    @Test
    void intern_onboarding_announced_uses_role_specific_deep_link() {
        // The wiring passes role-specific dashboard paths: trainer /
        // evaluator / manager. Template renders whatever deepLink it's
        // given — sanity-check all three variants render cleanly.
        for (String role : List.of("trainer", "evaluator", "manager")) {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("firstName", "Staff");
            vars.put("internName", "Alice");
            vars.put("deepLink",
                    "https://careers.acme.example/careers/" + role + "/active-interns");
            var r = renderSeeded("INTERN_ONBOARDING_ANNOUNCED", vars);
            assertRenderClean(r, "INTERN_ONBOARDING_ANNOUNCED (" + role + ")");
            assertTrue(r.body().contains("/careers/" + role + "/active-interns"),
                    "body must carry the " + role + "-specific deep link");
        }
    }

    // ── OFFER_SIGN_REMINDER render tests ──────────────────────────────

    @Test
    void offer_sign_reminder_renders_with_wiring_vars() {
        // Var map matches OnboardingTrackerService.sendSignatureReminder:
        // firstName (intern), ermName, deepLink.
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("ermName", "Bob ERM");
        vars.put("deepLink",
                "https://careers.acme.example/careers/intern/offers");
        var r = renderSeeded("OFFER_SIGN_REMINDER", vars);
        assertRenderClean(r, "OFFER_SIGN_REMINDER");
        assertTrue(r.subject().contains("Acme Tech"),
                "subject must inject the brand name");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("Bob ERM"),
                "body must name the ERM who is sending the reminder");
        assertTrue(r.body().contains(
                "https://careers.acme.example/careers/intern/offers"));
    }

    @Test
    void offer_sign_reminder_renders_when_erm_name_is_unresolved() {
        // The wiring passes "your ERM" when caller.getFullName() is
        // blank/null. Template must render coherently.
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("ermName", "your ERM");
        vars.put("deepLink", "https://x/offers");
        var r = renderSeeded("OFFER_SIGN_REMINDER", vars);
        assertRenderClean(r, "OFFER_SIGN_REMINDER (unresolved ERM)");
        assertTrue(r.body().contains("your ERM"),
                "body must gracefully carry the 'your ERM' fallback");
    }

    // ── Rebrand-safety cross-check ────────────────────────────────────

    @Test
    void slice6c_seeds_have_no_literal_anvi_after_brandify() {
        for (String key : List.of("INTERN_ONBOARDING_ANNOUNCED", "OFFER_SIGN_REMINDER")) {
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

    // ── Grep-proof: OnboardingTrackerService wiring is template-first ─

    /** After Slice 6c, {@code OnboardingTrackerService} renders BOTH
     *  new templates. Presence of the template keys inside a
     *  {@code templateService.render(...)} call is the invariant. */
    @Test
    void onboarding_tracker_uses_template_render_for_both_sites() throws IOException {
        String source = readSource(
                "apps/backend/src/main/java/com/anvicorp/api/erm/newhire/"
                        + "OnboardingTrackerService.java");
        // Whitespace-agnostic: strip all whitespace so any Java formatting
        // (single-line or wrapped) is recognized.
        String stripped = source.replaceAll("\\s+", "");
        for (String key : List.of("INTERN_ONBOARDING_ANNOUNCED", "OFFER_SIGN_REMINDER")) {
            assertTrue(stripped.contains("templateService.render(\"" + key + "\""),
                    "OnboardingTrackerService must render template " + key
                            + " (Slice-6c template-first invariant).");
        }
    }

    /** Grep-proof — after Slice 6c, the sendBrandedHtml calls that
     *  carried the pre-migration inline bodies are gone; both sends
     *  route through emailProvider.sendRendered instead. */
    @Test
    void onboarding_tracker_no_longer_calls_sendBrandedHtml() throws IOException {
        String source = readSource(
                "apps/backend/src/main/java/com/anvicorp/api/erm/newhire/"
                        + "OnboardingTrackerService.java");
        assertFalse(source.contains("emailProvider.sendBrandedHtml"),
                "OnboardingTrackerService still calls sendBrandedHtml — Slice 6c "
                        + "fold-in regressed (both sites should use sendRendered "
                        + "with rendered template body).");
        assertTrue(source.contains("emailProvider.sendRendered("),
                "OnboardingTrackerService must send via sendRendered post-Slice-6c.");
    }

    /** Rebrand-safe — the file must not embed any literal
     *  {@code "Anvi Corp"} string. Brand tokens flow via the seeded
     *  templates (brandify at seed time) or via {@code brand.getName()}
     *  in the rebrand-safe fallback. */
    @Test
    void onboarding_tracker_has_no_literal_anvi_corp_string() throws IOException {
        String source = readSource(
                "apps/backend/src/main/java/com/anvicorp/api/erm/newhire/"
                        + "OnboardingTrackerService.java");
        assertFalse(source.contains("\"Anvi Corp\""),
                "OnboardingTrackerService embeds literal \"Anvi Corp\" — "
                        + "rebrand-safety broken.");
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static String readSource(String path) throws IOException {
        Path p = Path.of(path);
        if (!Files.exists(p)) {
            String stripped = path.startsWith("apps/backend/")
                    ? path.substring("apps/backend/".length()) : path;
            p = Path.of(stripped);
        }
        assertTrue(Files.exists(p), path + " must exist for grep-proof test");
        return Files.readString(p, StandardCharsets.UTF_8);
    }

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
