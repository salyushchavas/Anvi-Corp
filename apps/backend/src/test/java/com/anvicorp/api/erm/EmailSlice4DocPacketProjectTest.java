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
 * Email Slice 4 — doc-packet + project-assignment bridged-mirror fold-ins.
 *
 * <p>Two intern-facing internal-mailbox bridge legs were carrying inline
 * body construction that duplicated (and could drift from) templates
 * that already existed for the personal-Gmail leg:</p>
 *
 * <ul>
 *   <li>{@code DocumentEmailListener.onPacketAssigned} — the bridged
 *       mirror at lines 92-110 built inline copy that duplicated the
 *       {@code DOCUMENT_PACKET_ASSIGNED} template used on the SmtpEmail
 *       leg. ERM template edits would only flow to the personal mail;
 *       the company-mailbox copy would silently diverge.</li>
 *   <li>{@code ProjectNotificationDispatcher.dispatchProjectAssignedInternal}
 *       — a prior revision deliberately replaced a {@code PROJECT_ASSIGNED}
 *       template render with inline construction so the copy could go
 *       through {@code notifyIntern} (bridge). Slice 4 restores the
 *       render AND keeps the bridge (render → pass rendered subject/body
 *       to {@code notifyIntern}) — same pattern used in Slices 1-3.</li>
 * </ul>
 *
 * <p>Only the EMAIL body source changes (inline → template render).
 * Recipients (intern in both cases), in-app dispatch, and timing are
 * preserved exactly. Both templates already existed in the seeder;
 * Slice 4 adds no new seeds.</p>
 */
class EmailSlice4DocPacketProjectTest {

    private static final BrandConfig BRAND = new BrandConfig(
            "Acme Tech", "Acme Careers", "Acme Technologies LLC", "hello@acme.example");

    // ── Both templates are seeded already ─────────────────────────────

    @Test
    void both_slice4_templates_are_registered() {
        assertSeedExists("DOCUMENT_PACKET_ASSIGNED");
        assertSeedExists("PROJECT_ASSIGNED");
    }

    // ── FOLD-IN 1: DOCUMENT_PACKET_ASSIGNED render smoke test ─────────

    @Test
    void document_packet_assigned_renders_with_listener_shaped_vars() {
        // Var map matches DocumentEmailListener.onPacketAssigned exactly:
        // firstName, ermName, templateCount, templateTitlesList, deepLink.
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("ermName", "Bob ERM");
        vars.put("templateCount", 3);
        vars.put("templateTitlesList",
                " · Direct Deposit Form\n · Emergency Contact\n · Tax W-4\n");
        vars.put("deepLink",
                "https://careers.acme.example/careers/intern/documents");
        var r = renderSeeded("DOCUMENT_PACKET_ASSIGNED", vars);
        assertRenderClean(r, "DOCUMENT_PACKET_ASSIGNED");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("Bob ERM"));
        assertTrue(r.body().contains("3"),
                "body must show the template count");
        assertTrue(r.body().contains("Direct Deposit Form"));
        assertTrue(r.body().contains("Emergency Contact"));
        assertTrue(r.body().contains("Tax W-4"));
        assertTrue(r.body().contains(
                "https://careers.acme.example/careers/intern/documents"));
    }

    // ── FOLD-IN 2: PROJECT_ASSIGNED render smoke test ─────────────────

    @Test
    void project_assigned_renders_with_dispatcher_shaped_vars() {
        // Var map matches ProjectNotificationDispatcher fold-in exactly:
        // firstName, trainerName, projectTitle, technologyArea,
        // dueDateLocal, deepLink.
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("trainerName", "Bob Trainer");
        vars.put("projectTitle", "Build the API gateway");
        vars.put("technologyArea", "Java + Spring Boot");
        vars.put("dueDateLocal", "2026-11-30");
        vars.put("deepLink",
                "https://careers.acme.example/careers/intern/projects/p-1");
        var r = renderSeeded("PROJECT_ASSIGNED", vars);
        assertRenderClean(r, "PROJECT_ASSIGNED");
        assertTrue(r.subject().contains("Build the API gateway"),
                "subject must name the project");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("Bob Trainer"));
        assertTrue(r.body().contains("Java + Spring Boot"));
        assertTrue(r.body().contains("2026-11-30"));
        assertTrue(r.body().contains(
                "https://careers.acme.example/careers/intern/projects/p-1"));
    }

    @Test
    void project_assigned_accepts_no_tech_tag_placeholder() {
        // The dispatcher supplies "no tech tag" when project.getTechStack()
        // is null/blank; the template renders it without breaking. Locks
        // in the wiring's fallback string vs. an unresolved placeholder.
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("trainerName", "Bob");
        vars.put("projectTitle", "P");
        vars.put("technologyArea", "no tech tag");
        vars.put("dueDateLocal", "2026-12-01");
        vars.put("deepLink", "https://x/p");
        var r = renderSeeded("PROJECT_ASSIGNED", vars);
        assertRenderClean(r, "PROJECT_ASSIGNED (no-tech fallback)");
        assertTrue(r.body().contains("no tech tag"));
    }

    // ── Grep-proof: bridged mirror wiring is template-first ───────────

    /** After Slice 4, {@code DocumentEmailListener.onPacketAssigned}
     *  routes the bridged mirror through the shared
     *  {@code renderBridgedIntern} helper. The old inline construction
     *  ("has assigned you a document packet with … document(s) to
     *  complete") is gone. A regression that reintroduces it would push
     *  this substring count above zero. */
    @Test
    void document_listener_no_longer_carries_inline_packet_body() throws IOException {
        String source = readSource(
                "apps/backend/src/main/java/com/anvicorp/api/listener/"
                        + "DocumentEmailListener.java");
        assertFalse(source.contains("has assigned you a document packet with"),
                "DocumentEmailListener still carries the pre-Slice-4 inline "
                        + "packet body — Slice 4 fold-in regressed.");
        assertTrue(source.contains("renderBridgedIntern("),
                "DocumentEmailListener must route the bridged intern mail "
                        + "through the shared renderBridgedIntern helper.");
    }

    /** After Slice 4, {@code ProjectNotificationDispatcher} renders the
     *  PROJECT_ASSIGNED template. The old inline phrase
     *  ("has assigned you a new project:") should now appear at MOST
     *  once — inside the rebrand-safe fallback that only fires when the
     *  template row is absent. */
    @Test
    void project_dispatcher_uses_template_render_with_at_most_one_fallback_copy() throws IOException {
        String source = readSource(
                "apps/backend/src/main/java/com/anvicorp/api/trainer/projects/"
                        + "ProjectNotificationDispatcher.java");
        int inlineCount = countMatches(source, "has assigned you a new project:");
        assertTrue(inlineCount <= 1,
                "ProjectNotificationDispatcher contains " + inlineCount
                        + " copies of the pre-Slice-4 inline body — a "
                        + "regression re-added inline construction.");
        // Whitespace-agnostic check — the fold-in renders the template
        // whether the call is one-line or wrapped across lines.
        String collapsed = source.replaceAll("\\s+", " ");
        assertTrue(collapsed.contains("templateService.render( \"PROJECT_ASSIGNED\"")
                        || collapsed.contains("templateService.render(\"PROJECT_ASSIGNED\""),
                "ProjectNotificationDispatcher must render PROJECT_ASSIGNED "
                        + "on the intern leg.");
    }

    /** Rebrand-safe — neither migrated file may embed a literal
     *  {@code "Anvi Corp"} string (all brand tokens flow through
     *  {@code BrandConfig} at render time or in fallback via
     *  {@code brand.signoff()}). */
    @Test
    void migrated_files_carry_no_literal_anvi_corp_string() throws IOException {
        for (String path : List.of(
                "apps/backend/src/main/java/com/anvicorp/api/listener/"
                        + "DocumentEmailListener.java",
                "apps/backend/src/main/java/com/anvicorp/api/trainer/projects/"
                        + "ProjectNotificationDispatcher.java")) {
            String source = readSource(path);
            assertFalse(source.contains("\"Anvi Corp\""),
                    path + " embeds literal \"Anvi Corp\" — rebrand-safety broken.");
        }
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static String readSource(String path) throws IOException {
        Path p = Path.of(path);
        if (!Files.exists(p)) {
            // Test may run from either the backend module or the repo root.
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
