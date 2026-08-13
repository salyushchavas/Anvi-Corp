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
 * Email Slice 6a — DocumentEmailListener sibling bridged-mirror fold-ins.
 *
 * <p>Slice 4 built the {@code renderBridgedIntern} helper and migrated
 * the {@code onPacketAssigned} bridged mirror. This slice folds in the
 * four remaining siblings using the same helper:</p>
 *
 * <ol>
 *   <li>{@code onTaskReviewed} ACCEPT branch — was inline, now
 *       {@code DOCUMENT_TASK_ACCEPTED} template.</li>
 *   <li>{@code onTaskReviewed} REJECT branch — now
 *       {@code DOCUMENT_TASK_REJECTED} template.</li>
 *   <li>{@code onTaskReviewed} RESEND_REQUEST branch — now
 *       {@code DOCUMENT_TASK_RESEND} template (mapped to enum
 *       {@code DOCUMENT_TASK_RESEND_REQUESTED} — retained asymmetric
 *       naming for backward-compat with existing dispatchers).</li>
 *   <li>{@code onPacketCompleted} — now
 *       {@code DOCUMENT_PACKET_COMPLETED} template.</li>
 * </ol>
 *
 * <p>All four templates already existed in the seeder; no new seeds.
 * Only the EMAIL body source changes (inline → template render).
 * Recipients (intern in every case), in-app dispatch, and timing are
 * preserved exactly. Neither event is in
 * {@code EMAIL_ENABLED_EVENT_TYPES} so no double-send risk from the
 * auto-hook (verified in Slice 4 for onPacketAssigned; same allowlist
 * still applies).</p>
 */
class EmailSlice6aDocPacketSiblingsTest {

    private static final BrandConfig BRAND = new BrandConfig(
            "Acme Tech", "Acme Careers", "Acme Technologies LLC", "hello@acme.example");

    // ── All four templates are seeded already ─────────────────────────

    @Test
    void slice6a_templates_are_all_registered() {
        assertSeedExists("DOCUMENT_TASK_ACCEPTED");
        assertSeedExists("DOCUMENT_TASK_REJECTED");
        assertSeedExists("DOCUMENT_TASK_RESEND");
        assertSeedExists("DOCUMENT_PACKET_COMPLETED");
    }

    // ── DOCUMENT_TASK_ACCEPTED render smoke test ──────────────────────

    @Test
    void document_task_accepted_renders_with_wiring_shaped_vars() {
        // Var map matches DocumentEmailListener.onTaskReviewed exactly:
        // firstName, templateTitle, ermName, reasonHuman, ermComments,
        // deepLink, remainingTasksBlurb. Both the personal-Gmail leg
        // (renderAndSend) AND the bridged leg (renderBridgedIntern) now
        // use this same var map.
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("templateTitle", "Direct Deposit Form");
        vars.put("ermName", "Bob ERM");
        vars.put("reasonHuman", "");
        vars.put("ermComments", "");
        vars.put("deepLink",
                "https://careers.acme.example/careers/intern/documents");
        vars.put("remainingTasksBlurb", "2 documents left in your packet.");
        var r = renderSeeded("DOCUMENT_TASK_ACCEPTED", vars);
        assertRenderClean(r, "DOCUMENT_TASK_ACCEPTED");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("Direct Deposit Form"));
        assertTrue(r.body().contains("Bob ERM"));
        assertTrue(r.body().contains("2 documents left in your packet"));
    }

    // ── DOCUMENT_TASK_REJECTED render smoke test ──────────────────────

    @Test
    void document_task_rejected_renders_with_reason_and_comments() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("templateTitle", "W-4 Form");
        vars.put("ermName", "Bob ERM");
        vars.put("reasonHuman", "Missing signature");
        vars.put("ermComments", "Page 2 is blank; please sign and re-upload.");
        vars.put("deepLink",
                "https://careers.acme.example/careers/intern/documents");
        vars.put("remainingTasksBlurb", "");
        var r = renderSeeded("DOCUMENT_TASK_REJECTED", vars);
        assertRenderClean(r, "DOCUMENT_TASK_REJECTED");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("W-4 Form"));
        assertTrue(r.body().contains("Bob ERM"));
        assertTrue(r.body().contains("Missing signature"));
        assertTrue(r.body().contains("Page 2 is blank"));
    }

    // ── DOCUMENT_TASK_RESEND render smoke test ────────────────────────

    @Test
    void document_task_resend_renders_with_wiring_shaped_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("templateTitle", "Emergency Contact Form");
        vars.put("ermName", "Bob ERM");
        vars.put("reasonHuman", "Poor scan quality");
        vars.put("ermComments", "Please re-scan at a higher resolution.");
        vars.put("deepLink",
                "https://careers.acme.example/careers/intern/documents");
        vars.put("remainingTasksBlurb", "");
        var r = renderSeeded("DOCUMENT_TASK_RESEND", vars);
        assertRenderClean(r, "DOCUMENT_TASK_RESEND");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("Emergency Contact Form"));
        assertTrue(r.body().contains("Bob ERM"));
        assertTrue(r.body().contains("Poor scan quality"));
        assertTrue(r.body().contains("Please re-scan"));
    }

    // ── DOCUMENT_PACKET_COMPLETED render smoke test ───────────────────

    @Test
    void document_packet_completed_renders_with_wiring_shaped_vars() {
        // Var map matches DocumentEmailListener.onPacketCompleted:
        // firstName, tentativeStartDate, trainerName, evaluatorName,
        // managerName. Both the personal-Gmail leg AND the bridged leg
        // now use this same var map.
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("tentativeStartDate", "2026-11-30");
        vars.put("trainerName", "Trainer Tom");
        vars.put("evaluatorName", "Evaluator Eve");
        vars.put("managerName", "Manager Mia");
        var r = renderSeeded("DOCUMENT_PACKET_COMPLETED", vars);
        assertRenderClean(r, "DOCUMENT_PACKET_COMPLETED");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("2026-11-30"));
        assertTrue(r.body().contains("Trainer Tom"));
        assertTrue(r.body().contains("Evaluator Eve"));
        assertTrue(r.body().contains("Manager Mia"));
    }

    @Test
    void document_packet_completed_handles_tbd_team_names() {
        // The wiring supplies "TBD" via nameFor() when a team slot is
        // unassigned. Template must render cleanly.
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("tentativeStartDate", "your scheduled start date");
        vars.put("trainerName", "TBD");
        vars.put("evaluatorName", "TBD");
        vars.put("managerName", "TBD");
        var r = renderSeeded("DOCUMENT_PACKET_COMPLETED", vars);
        assertRenderClean(r, "DOCUMENT_PACKET_COMPLETED (TBD team)");
        assertTrue(r.body().contains("TBD"));
    }

    // ── Rebrand-safety cross-check ────────────────────────────────────

    @Test
    void slice6a_templates_have_no_literal_anvi_after_brandify() {
        for (String key : List.of("DOCUMENT_TASK_ACCEPTED",
                "DOCUMENT_TASK_REJECTED", "DOCUMENT_TASK_RESEND",
                "DOCUMENT_PACKET_COMPLETED")) {
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

    // ── Grep-proof: 4 sibling inline bridged bodies gone ──────────────

    /** After Slice 6a, {@code DocumentEmailListener} routes the four
     *  sibling bridged mirrors through {@code renderBridgedIntern}. The
     *  inline construction patterns ("String subject = " and
     *  "String plain = ") that used to build the bodies are gone; the
     *  helper is the sole source of truth for bridged intern mail. */
    @Test
    void document_listener_carries_no_inline_bridged_body_construction() throws IOException {
        String source = readSource(
                "apps/backend/src/main/java/com/anvicorp/api/listener/"
                        + "DocumentEmailListener.java");
        assertFalse(source.contains("String subject = "),
                "DocumentEmailListener still carries an inline 'String subject = ' — "
                        + "Slice 6a fold-in regressed.");
        assertFalse(source.contains("String plain = "),
                "DocumentEmailListener still carries an inline 'String plain = ' — "
                        + "Slice 6a fold-in regressed.");
        int helperCallCount = countMatches(source, "renderBridgedIntern(");
        // 1 helper definition + Slice-4 (onPacketAssigned) + Slice-6a
        // (onTaskReviewed + onPacketCompleted) = 4 occurrences total.
        assertTrue(helperCallCount >= 4,
                "Expected at least 4 renderBridgedIntern occurrences (definition + "
                        + "3 call sites); found " + helperCallCount);
    }

    /** Rebrand-safe — no literal {@code "Anvi Corp"} embedded in the
     *  migrated listener. All brand tokens flow via
     *  {@code brand.getName()} fallbacks or through the templates. */
    @Test
    void document_listener_carries_no_literal_anvi_corp_string() throws IOException {
        String source = readSource(
                "apps/backend/src/main/java/com/anvicorp/api/listener/"
                        + "DocumentEmailListener.java");
        assertFalse(source.contains("\"Anvi Corp\""),
                "DocumentEmailListener embeds literal \"Anvi Corp\" — "
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
