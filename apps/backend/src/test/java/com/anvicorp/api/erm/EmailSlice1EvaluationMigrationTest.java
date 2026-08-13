package com.anvicorp.api.erm;

import com.anvicorp.api.config.BrandConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Email Slice 1 — Evaluation-migration regressions. Covers the seven
 * intern-facing email sites in
 * {@link com.anvicorp.api.evaluator.EvaluationNotificationFanout} that
 * were rebuilt to render via the CommunicationTemplate system:
 *
 * <ol>
 *   <li>{@code evaluationScheduled} → EVALUATION_SCHEDULED</li>
 *   <li>{@code evaluationPublished} (ratings mail) → EVALUATION_PUBLISHED</li>
 *   <li>{@code evaluationPublished} (ack nudge) → EVALUATION_ACK_REQUESTED
 *       (Slice-1 new seed)</li>
 *   <li>{@code i983Scheduled} → I983_EVALUATION_SCHEDULED (Slice-1 new
 *       intern-facing seed; distinct from the pre-existing evaluator-
 *       facing I983_EVALUATION_DUE)</li>
 *   <li>{@code i983Published} → I983_EVALUATION_PUBLISHED</li>
 *   <li>{@code i983DsoSubmitted} → I983_DSO_SUBMITTED (Slice-1 new seed)</li>
 *   <li>{@code i983Amended} → I983_EVALUATION_AMENDED (Slice-1 new seed)</li>
 * </ol>
 *
 * <p>Each test drives the seeded template body through the real
 * {@link CommunicationTemplateService} render path with the exact
 * variable set the fanout code supplies, and asserts:</p>
 * <ul>
 *   <li>the seed exists,</li>
 *   <li>the render succeeds,</li>
 *   <li>no {@code {{placeholder}}} tokens leak into the output,</li>
 *   <li>no literal "Anvi" leaks (brand-var auto-injection worked),</li>
 *   <li>the caller-supplied values are visible in the body.</li>
 * </ul>
 *
 * <p>Also asserts the source file itself carries zero inline
 * {@code String subject = "…"} / {@code String plain = "…"} body
 * constructions outside the {@code renderAndEmail} helper — the
 * grep-proof invariant Slice 1 established.</p>
 */
class EmailSlice1EvaluationMigrationTest {

    private static final BrandConfig BRAND = new BrandConfig(
            "Acme Tech", "Acme Careers", "Acme Technologies LLC", "hello@acme.example");

    // ── Slice-1 seeds are registered ──────────────────────────────────

    @Test
    void slice1_new_seeds_are_registered() {
        assertSeedExists("EVALUATION_ACK_REQUESTED");
        assertSeedExists("I983_EVALUATION_SCHEDULED");
        assertSeedExists("I983_DSO_SUBMITTED");
        assertSeedExists("I983_EVALUATION_AMENDED");
    }

    @Test
    void slice1_existing_seeds_still_present() {
        assertSeedExists("EVALUATION_SCHEDULED");
        assertSeedExists("EVALUATION_PUBLISHED");
        assertSeedExists("I983_EVALUATION_PUBLISHED");
    }

    // ── Per-template render smoke tests (fanout-shaped var maps) ──────

    @Test
    void evaluation_scheduled_renders_with_fanout_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("evaluatorName", "Bob Evaluator");
        vars.put("evaluationType", "monthly");
        vars.put("scheduledDateLocal", "Wed Apr 15, 2026 at 3:00 PM UTC");
        vars.put("timezone", "UTC");
        vars.put("zoomLink", "https://zoom.example/j/123");
        var r = renderSeeded("EVALUATION_SCHEDULED", vars);
        assertRenderClean(r, "EVALUATION_SCHEDULED");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("Bob Evaluator"));
        assertTrue(r.body().contains("monthly"));
        assertTrue(r.body().contains("https://zoom.example/j/123"));
        assertTrue(r.body().endsWith("— Acme Tech"),
                "signoffBlock should resolve to the brand line");
    }

    @Test
    void evaluation_published_renders_with_fanout_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("evaluatorName", "Bob Evaluator");
        vars.put("evaluationType", "monthly");
        vars.put("ackDays", "3");
        vars.put("summaryLine", "Overall 4 / 5 · continue");
        vars.put("deepLink", "https://careers.acme.example/careers/intern/evaluations/e1");
        var r = renderSeeded("EVALUATION_PUBLISHED", vars);
        assertRenderClean(r, "EVALUATION_PUBLISHED");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("Bob Evaluator"));
        assertTrue(r.body().contains("Overall 4 / 5"));
        assertTrue(r.body().contains(
                "https://careers.acme.example/careers/intern/evaluations/e1"));
    }

    @Test
    void evaluation_ack_requested_renders_with_fanout_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("evaluatorName", "Bob Evaluator");
        vars.put("deepLink", "https://careers.acme.example/careers/intern/evaluations/e1");
        var r = renderSeeded("EVALUATION_ACK_REQUESTED", vars);
        assertRenderClean(r, "EVALUATION_ACK_REQUESTED");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("Bob Evaluator"));
        assertTrue(r.body().contains("acknowledgment"),
                "body should surface the ack action");
        assertTrue(r.body().contains(
                "https://careers.acme.example/careers/intern/evaluations/e1"));
        assertEquals("Action needed: acknowledge your evaluation", r.subject());
    }

    @Test
    void i983_evaluation_scheduled_renders_with_fanout_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("evaluatorName", "Bob Evaluator");
        vars.put("evaluationType", "12 MONTH");
        vars.put("windowStartDate", "2026-04-01");
        vars.put("dueDate", "2026-04-30");
        // Fanout supplies these two extras (from the shared vars map);
        // template ignores them but the renderer must not throw.
        vars.put("internName", "Alice Intern");
        vars.put("employeeId", "EMP-1234");
        vars.put("deepLink", "https://careers.acme.example/careers/intern/i983-evaluations");
        var r = renderSeeded("I983_EVALUATION_SCHEDULED", vars);
        assertRenderClean(r, "I983_EVALUATION_SCHEDULED");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("Bob Evaluator"));
        assertTrue(r.body().contains("12 MONTH"));
        assertTrue(r.body().contains("2026-04-01"));
        assertTrue(r.body().contains("2026-04-30"));
        assertTrue(r.subject().contains("12 MONTH"));
        assertTrue(r.subject().startsWith("I-983"));
    }

    @Test
    void i983_evaluation_published_renders_with_fanout_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("evaluatorName", "Bob Evaluator");
        vars.put("evaluationType", "12 MONTH");
        vars.put("deepLink", "https://careers.acme.example/careers/intern/i983-evaluations/e1");
        var r = renderSeeded("I983_EVALUATION_PUBLISHED", vars);
        assertRenderClean(r, "I983_EVALUATION_PUBLISHED");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("Bob Evaluator"));
        assertTrue(r.body().contains("12 MONTH"));
        assertTrue(r.body().contains(
                "https://careers.acme.example/careers/intern/i983-evaluations/e1"));
    }

    @Test
    void i983_dso_submitted_renders_with_fanout_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("actorName", "Bob ERM");
        vars.put("submissionMethod", "email attachment");
        vars.put("deepLink", "https://careers.acme.example/careers/intern/i983-evaluations/e1");
        var r = renderSeeded("I983_DSO_SUBMITTED", vars);
        assertRenderClean(r, "I983_DSO_SUBMITTED");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("Bob ERM"));
        assertTrue(r.body().contains("email attachment"));
        assertTrue(r.body().contains("DSO"));
        assertEquals("Your I-983 was submitted to your DSO", r.subject());
    }

    @Test
    void i983_evaluation_amended_renders_with_fanout_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("evaluatorName", "Bob Evaluator");
        vars.put("changeSummary", "Section 5 metrics revised");
        vars.put("deepLink", "https://careers.acme.example/careers/intern/i983-evaluations/e1");
        var r = renderSeeded("I983_EVALUATION_AMENDED", vars);
        assertRenderClean(r, "I983_EVALUATION_AMENDED");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("Bob Evaluator"));
        assertTrue(r.body().contains("Section 5 metrics revised"));
        assertTrue(r.body().contains("re-sign"));
        assertEquals("Your I-983 was updated — please re-sign", r.subject());
    }

    // ── Grep-proof: fanout carries no inline body construction ────────

    /** After Slice 1, every intern-facing email in
     *  {@code EvaluationNotificationFanout} routes through
     *  {@code renderInternMail(...)} / {@code renderInternMailFromRole(...)}.
     *  The only remaining {@code String subject = } / {@code String plain = }
     *  lines are inside the {@code renderAndEmail} helper where they READ
     *  from the rendered template (they don't CONSTRUCT an inline body).
     *  A regression that reintroduces inline construction would push this
     *  count above the two allowed helper lines. */
    @Test
    void fanout_has_no_inline_subject_or_plain_body_construction_at_call_sites() throws IOException {
        Path fanout = Path.of("src/main/java/com/anvicorp/api/evaluator/"
                + "EvaluationNotificationFanout.java");
        // Fall back to the mono-repo path when the test is run from the
        // repo root instead of the backend module.
        if (!Files.exists(fanout)) {
            fanout = Path.of("apps/backend/src/main/java/com/anvicorp/api/evaluator/"
                    + "EvaluationNotificationFanout.java");
        }
        assertTrue(Files.exists(fanout),
                "EvaluationNotificationFanout.java must exist for grep-proof test");
        String source = Files.readString(fanout, StandardCharsets.UTF_8);
        // Count occurrences of "String subject = " and "String plain = ".
        int subjectCount = countMatches(source, "String subject = ");
        int plainCount = countMatches(source, "String plain = ");
        // Exactly 1 of each remains (both inside the renderAndEmail helper
        // that READS from the rendered template). Above 1 means an inline
        // body construction crept back in.
        assertEquals(1, subjectCount,
                "Fanout must have exactly 1 'String subject = ' line (inside renderAndEmail); "
                        + "extras mean an inline body construction was reintroduced.");
        assertEquals(1, plainCount,
                "Fanout must have exactly 1 'String plain = ' line (inside renderAndEmail); "
                        + "extras mean an inline body construction was reintroduced.");
    }

    /** Grep-proof — the file must not reference the brand-signoff helpers
     *  at intern-mail call sites (templates carry {{signoffBlock}}
     *  themselves). {@code brand.signoff()} / {@code brand.signoffErm()}
     *  used to appear at every inline body construction; after Slice 1
     *  they should be gone. */
    @Test
    void fanout_no_longer_calls_brand_signoff_helpers() throws IOException {
        Path fanout = Path.of("src/main/java/com/anvicorp/api/evaluator/"
                + "EvaluationNotificationFanout.java");
        if (!Files.exists(fanout)) {
            fanout = Path.of("apps/backend/src/main/java/com/anvicorp/api/evaluator/"
                    + "EvaluationNotificationFanout.java");
        }
        String source = Files.readString(fanout, StandardCharsets.UTF_8);
        assertFalse(source.contains("brand.signoff"),
                "brand.signoff*() must not be called from the fanout after Slice 1 — "
                        + "the templates carry {{signoffBlock}} which resolves via "
                        + "BrandConfig at render time.");
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static void assertSeedExists(String key) {
        boolean found = allSeeds().stream()
                .anyMatch(s -> key.equals(s.key()) && "EMAIL".equals(s.channel()));
        assertTrue(found, key + " EMAIL seed must be registered in CommunicationTemplateSeeder");
    }

    /** Render a seeded template through the real
     *  {@link CommunicationTemplateService} with brand auto-injection. */
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

    /** Common assertions every Slice-1 render must satisfy. */
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
