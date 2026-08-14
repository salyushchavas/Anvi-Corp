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
 * Email Slice 6e — Category A final subset. 12 NotificationService
 * legacy typed methods migrated to template-first with typed fallback,
 * closing the bulk of Category A. 11 new templates seeded + 1 reused
 * ({@code PROJECT_ASSIGNED} from Slice 4).
 *
 * <ol>
 *   <li>sendOfferAcceptedToOps → new OFFER_ACCEPTED_OPS</li>
 *   <li>sendI983PlanNeeded → new I983_PLAN_NEEDED</li>
 *   <li>sendI983PlanReady → new I983_PLAN_READY</li>
 *   <li>sendProjectAssigned → reuses PROJECT_ASSIGNED (Slice 4)</li>
 *   <li>sendProjectSubmitted → new PROJECT_SUBMITTED</li>
 *   <li>sendProjectReturned → new PROJECT_RETURNED</li>
 *   <li>sendProjectCompleted → new PROJECT_COMPLETED</li>
 *   <li>sendProjectTechApproved → new PROJECT_TECH_APPROVED</li>
 *   <li>sendProjectReturnedForRevisions → new PROJECT_RETURNED_FOR_REVISIONS</li>
 *   <li>sendProjectPendingViva → new PROJECT_PENDING_VIVA</li>
 *   <li>sendEvaluationDue → new EVALUATION_DUE (legacy Evaluation entity;
 *       distinct from EvaluationNotificationFanout's InternEvaluation flow)</li>
 *   <li>sendEvaluationFinalized → new EVALUATION_FINALIZED (same
 *       distinction — legacy Evaluation entity)</li>
 * </ol>
 *
 * <p>All 12 confirmed hardcoded, none redundant with existing template
 * paths (the two evaluation methods operate on the legacy Evaluation
 * entity; EvaluationNotificationFanout handles the separate
 * InternEvaluation entity via its own templates). Batch-7 Option-B
 * placeholder convention used in new seeds: {@code {{signoffBlock}}}
 * and {@code {{brandName}}} render at template-service time, no
 * {@code brandify()} rewrite needed.</p>
 */
class EmailSlice6eCategoryAFinalTest {

    private static final BrandConfig BRAND = new BrandConfig(
            "Acme Tech", "Acme Careers", "Acme Technologies LLC", "hello@acme.example");

    // ── All 12 templates registered (11 new + 1 reused) ───────────────

    @Test
    void slice6e_templates_are_all_registered() {
        // 12 new seeds (11 initial + I9_SECTION2_PENDING follow-up)
        assertSeedExists("OFFER_ACCEPTED_OPS");
        assertSeedExists("I983_PLAN_NEEDED");
        assertSeedExists("I983_PLAN_READY");
        assertSeedExists("PROJECT_SUBMITTED");
        assertSeedExists("PROJECT_RETURNED");
        assertSeedExists("PROJECT_COMPLETED");
        assertSeedExists("PROJECT_TECH_APPROVED");
        assertSeedExists("PROJECT_RETURNED_FOR_REVISIONS");
        assertSeedExists("PROJECT_PENDING_VIVA");
        assertSeedExists("EVALUATION_DUE");
        assertSeedExists("EVALUATION_FINALIZED");
        assertSeedExists("I9_SECTION2_PENDING");
        // Reused template (must still be present).
        assertSeedExists("PROJECT_ASSIGNED");
    }

    @Test
    void i9_section2_pending_renders_with_wiring_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("internName", "Alice Intern");
        vars.put("dueDate", "2026-09-05");
        vars.put("deepLink", "https://x/hr/i9");
        var r = renderSeeded("I9_SECTION2_PENDING", vars);
        assertRenderClean(r, "I9_SECTION2_PENDING");
        assertTrue(r.subject().contains("Alice Intern"));
        assertTrue(r.body().contains("Alice Intern"));
        assertTrue(r.body().contains("2026-09-05"));
        assertTrue(r.body().contains("3 business days"),
                "body must state the federal 3-business-day rule");
    }

    // ── Per-template render smoke tests ───────────────────────────────

    @Test
    void offer_accepted_ops_renders_with_wiring_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("applicantName", "Alice Applicant");
        vars.put("applicantEmail", "alice@example.com");
        vars.put("jobTitle", "Software Engineer");
        vars.put("entityName", "Acme Tech Staffing LLC");
        vars.put("startDate", "2026-09-01");
        var r = renderSeeded("OFFER_ACCEPTED_OPS", vars);
        assertRenderClean(r, "OFFER_ACCEPTED_OPS");
        assertTrue(r.subject().contains("Alice Applicant"));
        assertTrue(r.subject().contains("Software Engineer"));
        assertTrue(r.body().contains("alice@example.com"));
        assertTrue(r.body().contains("Acme Tech Staffing LLC"));
        assertTrue(r.body().contains("Acme Tech"),
                "signoff must render brandName");
    }

    @Test
    void i983_plan_needed_renders_with_wiring_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("deepLink", "https://x/dashboard");
        var r = renderSeeded("I983_PLAN_NEEDED", vars);
        assertRenderClean(r, "I983_PLAN_NEEDED");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("STEM-OPT"));
    }

    @Test
    void i983_plan_ready_renders_with_wiring_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("internName", "Alice Intern");
        vars.put("deepLink", "https://x/hr");
        var r = renderSeeded("I983_PLAN_READY", vars);
        assertRenderClean(r, "I983_PLAN_READY");
        assertTrue(r.subject().contains("Alice Intern"));
        assertTrue(r.body().contains("Alice Intern"));
        assertTrue(r.body().contains("DSO"));
    }

    @Test
    void project_assigned_renders_with_legacy_wiring_fallback_supervisor() {
        // Legacy path may have a non-trainer assigner; wiring passes
        // "your supervisor" as trainerName fallback.
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("trainerName", "your supervisor");
        vars.put("projectTitle", "Build the API gateway");
        vars.put("technologyArea", "Java + Spring");
        vars.put("dueDateLocal", "2026-09-15");
        vars.put("deepLink", "https://x/proj");
        var r = renderSeeded("PROJECT_ASSIGNED", vars);
        assertRenderClean(r, "PROJECT_ASSIGNED (legacy path)");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("Build the API gateway"));
        assertTrue(r.body().contains("your supervisor"));
    }

    @Test
    void project_submitted_renders_with_wiring_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("supervisorName", "Bob");
        vars.put("internName", "Alice Intern");
        vars.put("projectTitle", "REST API");
        vars.put("deepLink", "https://x/review");
        var r = renderSeeded("PROJECT_SUBMITTED", vars);
        assertRenderClean(r, "PROJECT_SUBMITTED");
        assertTrue(r.subject().contains("Alice Intern"));
        assertTrue(r.subject().contains("REST API"));
        assertTrue(r.body().contains("Bob"));
    }

    @Test
    void project_returned_renders_with_review_notes() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("projectTitle", "REST API");
        vars.put("reviewNotesLine",
                "\n\nReviewer notes: Add authentication section.");
        vars.put("deepLink", "https://x/proj");
        var r = renderSeeded("PROJECT_RETURNED", vars);
        assertRenderClean(r, "PROJECT_RETURNED");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("REST API"));
        assertTrue(r.body().contains("Add authentication section"));
    }

    @Test
    void project_returned_renders_cleanly_without_review_notes() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("projectTitle", "REST API");
        vars.put("reviewNotesLine", "");
        vars.put("deepLink", "https://x/proj");
        var r = renderSeeded("PROJECT_RETURNED", vars);
        assertRenderClean(r, "PROJECT_RETURNED (no notes)");
        assertFalse(r.body().contains("Reviewer notes:"),
                "no 'Reviewer notes:' label when block is empty");
    }

    @Test
    void project_completed_renders_with_wiring_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("projectTitle", "REST API");
        vars.put("deepLink", "https://x/proj");
        var r = renderSeeded("PROJECT_COMPLETED", vars);
        assertRenderClean(r, "PROJECT_COMPLETED");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("REST API"));
        assertTrue(r.body().contains("Congratulations"));
    }

    @Test
    void project_tech_approved_renders_with_wiring_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("projectTitle", "REST API");
        vars.put("deepLink", "https://x/proj");
        var r = renderSeeded("PROJECT_TECH_APPROVED", vars);
        assertRenderClean(r, "PROJECT_TECH_APPROVED");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("REST API"));
        assertTrue(r.body().contains("viva"),
                "body must reference the next step (viva)");
    }

    @Test
    void project_returned_for_revisions_renders_with_reason_block() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("projectTitle", "REST API");
        vars.put("reasonBlock", "\n\nReason: Missing test coverage.");
        vars.put("deepLink", "https://x/proj");
        var r = renderSeeded("PROJECT_RETURNED_FOR_REVISIONS", vars);
        assertRenderClean(r, "PROJECT_RETURNED_FOR_REVISIONS");
        assertTrue(r.body().contains("Missing test coverage"));
    }

    @Test
    void project_returned_for_revisions_renders_cleanly_without_reason() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("projectTitle", "REST API");
        vars.put("reasonBlock", "");
        vars.put("deepLink", "https://x/proj");
        var r = renderSeeded("PROJECT_RETURNED_FOR_REVISIONS", vars);
        assertRenderClean(r, "PROJECT_RETURNED_FOR_REVISIONS (no reason)");
        assertFalse(r.body().contains("Reason:"),
                "no 'Reason:' label when block is empty");
    }

    @Test
    void project_pending_viva_renders_with_wiring_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("projectTitle", "REST API");
        vars.put("deepLink", "https://x/proj");
        var r = renderSeeded("PROJECT_PENDING_VIVA", vars);
        assertRenderClean(r, "PROJECT_PENDING_VIVA");
        assertTrue(r.body().contains("Reporting Manager"));
    }

    @Test
    void evaluation_due_renders_with_singular_and_plural_day_suffix() {
        // Wiring computes daysPluralSuffix "" for 1 day, "s" otherwise.
        for (Object[] pair : new Object[][]{{1, ""}, {5, "s"}, {14, "s"}}) {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("supervisorName", "Bob");
            vars.put("internName", "Alice Intern");
            vars.put("evaluationType", "MONTHLY");
            vars.put("daysInDraft", pair[0]);
            vars.put("daysPluralSuffix", pair[1]);
            vars.put("deepLink", "https://x/eval");
            var r = renderSeeded("EVALUATION_DUE", vars);
            assertRenderClean(r, "EVALUATION_DUE (" + pair[0] + " days)");
            assertTrue(r.body().contains(pair[0] + " day" + pair[1]),
                    "body must correctly show plural/singular day count");
        }
    }

    @Test
    void evaluation_finalized_renders_with_wiring_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("evaluationType", "MONTHLY");
        vars.put("supervisorLine", " by Bob Evaluator");
        vars.put("overallRating", "4.5");
        vars.put("deepLink", "https://x/eval/1");
        var r = renderSeeded("EVALUATION_FINALIZED", vars);
        assertRenderClean(r, "EVALUATION_FINALIZED");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("Bob Evaluator"));
        assertTrue(r.body().contains("4.5"));
    }

    @Test
    void evaluation_finalized_renders_cleanly_without_supervisor_line() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("evaluationType", "MONTHLY");
        vars.put("supervisorLine", "");
        vars.put("overallRating", "—");
        vars.put("deepLink", "https://x/eval");
        var r = renderSeeded("EVALUATION_FINALIZED", vars);
        assertRenderClean(r, "EVALUATION_FINALIZED (no supervisor)");
        assertFalse(r.body().contains(" by "),
                "no ' by ' fragment when supervisorLine is empty");
    }

    // ── Rebrand-safety cross-check on 11 new seeds ────────────────────

    @Test
    void slice6e_new_seeds_have_no_literal_anvi() {
        for (String key : List.of("OFFER_ACCEPTED_OPS", "I983_PLAN_NEEDED",
                "I983_PLAN_READY", "PROJECT_SUBMITTED", "PROJECT_RETURNED",
                "PROJECT_COMPLETED", "PROJECT_TECH_APPROVED",
                "PROJECT_RETURNED_FOR_REVISIONS", "PROJECT_PENDING_VIVA",
                "EVALUATION_DUE", "EVALUATION_FINALIZED",
                "I9_SECTION2_PENDING")) {
            CommunicationTemplateSeeder.Seed raw = allSeeds().stream()
                    .filter(s -> key.equals(s.key()) && "EMAIL".equals(s.channel()))
                    .findFirst()
                    .orElseThrow();
            // Batch-7 Option-B: source uses {{signoffBlock}} + {{brandName}}
            // directly, no brandify() rewrite needed. Verify source has no
            // literal "Anvi" tokens at all.
            assertFalse(raw.subject().contains("Anvi"),
                    key + " subject source has literal Anvi (should use {{brandName}}): "
                            + raw.subject());
            assertFalse(raw.body().contains("Anvi"),
                    key + " body source has literal Anvi (should use {{brandName}}): "
                            + raw.body());
        }
    }

    // ── Grep-proof: NotificationService renders all 12 templates ──────

    @Test
    void notification_service_renders_all_thirteen_slice6e_templates() throws IOException {
        String source = readSource(
                "apps/backend/src/main/java/com/anvicorp/api/notification/"
                        + "NotificationService.java");
        String stripped = source.replaceAll("\\s+", "");
        for (String key : List.of("OFFER_ACCEPTED_OPS", "I983_PLAN_NEEDED",
                "I983_PLAN_READY", "PROJECT_ASSIGNED", "PROJECT_SUBMITTED",
                "PROJECT_RETURNED", "PROJECT_COMPLETED",
                "PROJECT_TECH_APPROVED", "PROJECT_RETURNED_FOR_REVISIONS",
                "PROJECT_PENDING_VIVA", "EVALUATION_DUE",
                "EVALUATION_FINALIZED", "I9_SECTION2_PENDING")) {
            assertTrue(stripped.contains("templateService.render(\"" + key + "\""),
                    "NotificationService must render template " + key
                            + " (Slice-6e invariant).");
        }
    }

    @Test
    void notification_service_retains_typed_fallback_for_each_migrated_method() throws IOException {
        String source = readSource(
                "apps/backend/src/main/java/com/anvicorp/api/notification/"
                        + "NotificationService.java");
        for (String typed : List.of(
                "emailProvider.sendOfferAcceptedToOps",
                "emailProvider.sendI983PlanNeeded",
                "emailProvider.sendI983PlanReady",
                "emailProvider.sendProjectAssigned",
                "emailProvider.sendProjectSubmitted",
                "emailProvider.sendProjectReturned",
                "emailProvider.sendProjectCompleted",
                "emailProvider.sendProjectTechApproved",
                "emailProvider.sendProjectReturnedForRevisions",
                "emailProvider.sendProjectPendingViva",
                "emailProvider.sendEvaluationDue",
                "emailProvider.sendEvaluationFinalized",
                "emailProvider.sendI9Section2Pending")) {
            assertTrue(source.contains(typed),
                    "NotificationService must retain " + typed + " as fallback.");
        }
    }

    @Test
    void notification_service_has_no_literal_anvi_corp_string() throws IOException {
        String source = readSource(
                "apps/backend/src/main/java/com/anvicorp/api/notification/"
                        + "NotificationService.java");
        assertFalse(source.contains("\"Anvi Corp\""),
                "NotificationService embeds literal \"Anvi Corp\" — rebrand-safety broken.");
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
        // Batch-7 Option B: Slice-6e seeds already use {{signoffBlock}}
        // directly. Slice-4's PROJECT_ASSIGNED (also tested here) still
        // uses the legacy "— Anvi Corp" source — brandify normalises
        // that. Run brandify unconditionally so both flavours render.
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
                key + " subject leaked literal Anvi: " + r.subject());
        assertFalse(r.body().contains("Anvi"),
                key + " body leaked literal Anvi: " + r.body());
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
