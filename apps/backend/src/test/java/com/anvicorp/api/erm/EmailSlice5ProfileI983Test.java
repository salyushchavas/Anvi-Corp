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
 * Email Slice 5 — profile alerts + I-983 self-eval-due fold-ins. Three
 * remaining hardcoded email bodies migrated to seeded
 * {@link CommunicationTemplateSeeder} templates:
 *
 * <ol>
 *   <li>{@code PROFILE_SUBMITTED} — ERM one-shot alert when an intern
 *       first meets the stricter completeness bar (base + address +
 *       education + expected work-auth track).
 *       {@code ProfileNotificationService.maybeFireSubmissionAck}.</li>
 *   <li>{@code PROFILE_EDITED} — ERM throttled alert (max 1/15min) on
 *       post-submission edits.
 *       {@code ProfileNotificationService.maybeNotifyOnEdit}.</li>
 *   <li>{@code I983_SELF_EVAL_DUE} — intern STEM-OPT compliance nudge.
 *       {@code NotificationService.sendI983SelfEvalDue} (template-first
 *       with the existing typed hardcoded method as fallback).</li>
 * </ol>
 *
 * <p>Only the email BODY source changed (inline → template render).
 * Recipients (each active ERM for profile alerts; the intern for I-983
 * self-eval), in-app dispatch, and timing are preserved exactly.</p>
 */
class EmailSlice5ProfileI983Test {

    private static final BrandConfig BRAND = new BrandConfig(
            "Acme Tech", "Acme Careers", "Acme Technologies LLC", "hello@acme.example");

    // ── Slice-5 seeds are registered ──────────────────────────────────

    @Test
    void slice5_new_seeds_are_registered() {
        assertSeedExists("PROFILE_SUBMITTED");
        assertSeedExists("PROFILE_EDITED");
        assertSeedExists("I983_SELF_EVAL_DUE");
    }

    // ── PROFILE_SUBMITTED render tests ────────────────────────────────

    @Test
    void profile_submitted_renders_with_wiring_shaped_vars() {
        // Var map matches ProfileNotificationService.submissionVars.
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("ermName", "Bob ERM");
        vars.put("internName", "Alice Applicant");
        vars.put("internEmail", "alice@example.com");
        vars.put("internPhone", "+1 555 0100");
        vars.put("workAuth", "F1_STEM_OPT, needs sponsorship");
        vars.put("skillset", "Java, Spring, React");
        vars.put("fullAddress",
                "123 Elm St, Apt 4B, Springfield, IL 62701, USA");
        vars.put("submittedAtLocal", "2026-08-13T15:30:00Z");
        vars.put("deepLink",
                "https://careers.acme.example/careers/erm/applications");
        var r = renderSeeded("PROFILE_SUBMITTED", vars);
        assertRenderClean(r, "PROFILE_SUBMITTED");
        assertTrue(r.subject().contains("Alice Applicant"));
        assertTrue(r.body().contains("Bob ERM"));
        assertTrue(r.body().contains("alice@example.com"));
        assertTrue(r.body().contains("+1 555 0100"));
        assertTrue(r.body().contains("F1_STEM_OPT"));
        assertTrue(r.body().contains("Java, Spring, React"));
        assertTrue(r.body().contains("123 Elm St"));
        assertTrue(r.body().contains(
                "https://careers.acme.example/careers/erm/applications"));
    }

    @Test
    void profile_submitted_handles_dashes_for_missing_optional_fields() {
        // The wiring passes "—" through nullSafe() when the intern hasn't
        // filled a field. Template must render cleanly (no unresolved
        // placeholders, no crash on "—" as a variable value).
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("ermName", "Bob");
        vars.put("internName", "Alice");
        vars.put("internEmail", "alice@x");
        vars.put("internPhone", "—");
        vars.put("workAuth", "—");
        vars.put("skillset", "—");
        vars.put("fullAddress", "—");
        vars.put("submittedAtLocal", "—");
        vars.put("deepLink", "https://x/apps");
        var r = renderSeeded("PROFILE_SUBMITTED", vars);
        assertRenderClean(r, "PROFILE_SUBMITTED (dashes)");
    }

    // ── PROFILE_EDITED render tests ───────────────────────────────────

    @Test
    void profile_edited_renders_with_wiring_shaped_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("ermName", "Bob ERM");
        vars.put("internName", "Alice");
        vars.put("internEmail", "alice@example.com");
        vars.put("internPhone", "+1 555 0100");
        vars.put("changedField", "phone_number");
        vars.put("skillset", "Java");
        vars.put("fullAddress", "123 Elm St, Springfield, IL");
        vars.put("workAuth", "OPT");
        vars.put("deepLink",
                "https://careers.acme.example/careers/erm/applications");
        var r = renderSeeded("PROFILE_EDITED", vars);
        assertRenderClean(r, "PROFILE_EDITED");
        assertTrue(r.subject().contains("Alice"));
        assertTrue(r.body().contains("Bob ERM"));
        assertTrue(r.body().contains("phone_number"),
                "changedField must be inlined");
        assertTrue(r.body().contains("15 minutes"),
                "throttle notice must be present");
    }

    // ── I983_SELF_EVAL_DUE render tests ───────────────────────────────

    @Test
    void i983_self_eval_due_renders_with_wiring_shaped_vars() {
        // Var map matches NotificationService.sendI983SelfEvalDue fold-in.
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("evaluationType", "STEM OPT 12 MONTH");
        vars.put("deepLink",
                "https://careers.acme.example/careers/intern/evaluations/e-1");
        var r = renderSeeded("I983_SELF_EVAL_DUE", vars);
        assertRenderClean(r, "I983_SELF_EVAL_DUE");
        assertTrue(r.subject().contains("STEM OPT 12 MONTH"),
                "subject must show the evaluation type");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("STEM OPT 12 MONTH"));
        assertTrue(r.body().contains("STEM-OPT"),
                "body must reference the federal STEM-OPT context");
        assertTrue(r.body().contains(
                "https://careers.acme.example/careers/intern/evaluations/e-1"));
    }

    @Test
    void i983_self_eval_due_handles_null_type_via_fallback_string() {
        // The wiring passes "I-983" when evaluation.getType() is null.
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("evaluationType", "I-983");
        vars.put("deepLink", "https://x/e");
        var r = renderSeeded("I983_SELF_EVAL_DUE", vars);
        assertRenderClean(r, "I983_SELF_EVAL_DUE (null type)");
        assertTrue(r.subject().contains("I-983"));
    }

    // ── Rebrand-safety cross-check ────────────────────────────────────

    @Test
    void slice5_seeds_have_no_literal_anvi_after_brandify() {
        for (String key : List.of("PROFILE_SUBMITTED", "PROFILE_EDITED",
                "I983_SELF_EVAL_DUE")) {
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

    // ── Grep-proof: wiring sites are template-first ───────────────────

    /** After Slice 5, {@code ProfileNotificationService} routes both
     *  alerts through {@code templateService.render(...)}. Presence of
     *  the render call anywhere in the file is the invariant. */
    @Test
    void profile_notification_service_uses_template_render() throws IOException {
        String source = readSource(
                "apps/backend/src/main/java/com/anvicorp/api/profile/"
                        + "ProfileNotificationService.java");
        String collapsed = source.replaceAll("\\s+", " ");
        assertTrue(collapsed.contains("templateService.render(templateKey"),
                "ProfileNotificationService must render via templateService.");
    }

    /** After Slice 5, {@code NotificationService.sendI983SelfEvalDue}
     *  attempts a template render before falling back to the typed
     *  hardcoded method. The presence of the render call for
     *  I983_SELF_EVAL_DUE is the migration invariant. */
    @Test
    void notification_service_i983_self_eval_due_is_template_first() throws IOException {
        String source = readSource(
                "apps/backend/src/main/java/com/anvicorp/api/notification/"
                        + "NotificationService.java");
        String collapsed = source.replaceAll("\\s+", " ");
        assertTrue(collapsed.contains(
                "templateService.render( \"I983_SELF_EVAL_DUE\"")
                        || collapsed.contains(
                                "templateService.render(\"I983_SELF_EVAL_DUE\""),
                "NotificationService.sendI983SelfEvalDue must render "
                        + "I983_SELF_EVAL_DUE template.");
    }

    /** Rebrand-safe — no migrated file may embed a literal
     *  {@code "Anvi Corp"} string. The seeded templates encode brand
     *  tokens as {@code {{brandName}}} / {@code {{signoffBlock}}} that
     *  {@code brandify()} rewrites at seed time; the wiring code uses
     *  {@code brand.getName()} fallbacks only. */
    @Test
    void migrated_files_carry_no_literal_anvi_corp_string() throws IOException {
        for (String path : List.of(
                "apps/backend/src/main/java/com/anvicorp/api/profile/"
                        + "ProfileNotificationService.java",
                "apps/backend/src/main/java/com/anvicorp/api/notification/"
                        + "NotificationService.java")) {
            String source = readSource(path);
            assertFalse(source.contains("\"Anvi Corp\""),
                    path + " embeds literal \"Anvi Corp\" — rebrand-safety broken.");
        }
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
