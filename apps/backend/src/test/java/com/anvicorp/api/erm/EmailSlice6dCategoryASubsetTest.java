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
 * Email Slice 6d — Category A next subset. 8 more NotificationService
 * legacy typed methods migrated to template-first with typed fallback
 * (same shape as Slice 6b): 4 reuse pre-existing templates, 4 use new
 * Slice-6d seeds.
 *
 * <ol>
 *   <li>{@code sendInterviewScheduled} → reuses INTERVIEW_SCHEDULED</li>
 *   <li>{@code sendOfferExtended} → new OFFER_EXTENDED template</li>
 *   <li>{@code sendEVerifyCaseOpened} → reuses EVERIFY_CASE_OPENED</li>
 *   <li>{@code sendEVerifyTncAlert} → reuses
 *       EVERIFY_TENTATIVE_NONCONFIRMATION</li>
 *   <li>{@code sendEVerifyCleared} → reuses EVERIFY_AUTHORIZED</li>
 *   <li>{@code sendWeeklyReportReturned} → new WEEKLY_REPORT_RETURNED</li>
 *   <li>{@code sendWeeklyReportApproved} → new WEEKLY_REPORT_APPROVED</li>
 *   <li>{@code sendI9Section1Reminder} → new I9_SECTION1_REMINDER</li>
 * </ol>
 *
 * <p>Recipients preserved (applicant / intern per method), typed
 * fallback retained in every branch (backward-safe if a template row
 * is absent). No double-send: for the 3 methods with a parallel
 * listener-driven template path (sendInterviewScheduled via
 * InterviewEmailListener; sendEVerifyCaseOpened / sendEVerify* via
 * ComplianceLifecycleListener), the two paths are triggered by
 * DIFFERENT entry points (legacy service call vs new ERM UI that
 * publishes a domain event) — each fires ONE email per event, not
 * both.</p>
 */
class EmailSlice6dCategoryASubsetTest {

    private static final BrandConfig BRAND = new BrandConfig(
            "Acme Tech", "Acme Careers", "Acme Technologies LLC", "hello@acme.example");

    // ── All 8 templates registered (4 new + 4 reused) ─────────────────

    @Test
    void slice6d_templates_are_all_registered() {
        // 4 new seeds this slice.
        assertSeedExists("OFFER_EXTENDED");
        assertSeedExists("WEEKLY_REPORT_RETURNED");
        assertSeedExists("WEEKLY_REPORT_APPROVED");
        assertSeedExists("I9_SECTION1_REMINDER");
        // 4 reused pre-existing templates — must remain present.
        assertSeedExists("INTERVIEW_SCHEDULED");
        assertSeedExists("EVERIFY_CASE_OPENED");
        assertSeedExists("EVERIFY_TENTATIVE_NONCONFIRMATION");
        assertSeedExists("EVERIFY_AUTHORIZED");
    }

    // ── Per-template render smoke tests (wiring-shaped var maps) ──────

    @Test
    void interview_scheduled_renders_with_legacy_wiring_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("jobTitle", "Backend Intern");
        vars.put("scheduledForLocal", "2026-08-20T15:30:00Z");
        vars.put("timezone", "UTC");
        vars.put("zoomJoinUrl", "https://zoom.example/j/x");
        vars.put("interviewerName", "Bob Interviewer");
        vars.put("prepInstructions", "Review the JD; be ready with 2-3 questions.");
        var r = renderSeeded("INTERVIEW_SCHEDULED", vars);
        assertRenderClean(r, "INTERVIEW_SCHEDULED");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("Bob Interviewer"));
        assertTrue(r.body().contains("Backend Intern"));
        assertTrue(r.body().contains("https://zoom.example/j/x"));
    }

    @Test
    void offer_extended_renders_with_wiring_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("jobTitle", "Software Engineer");
        vars.put("entityName", "Acme Tech Staffing LLC");
        vars.put("compensationLine", "85000 USD / annual");
        vars.put("startDate", "2026-09-01");
        vars.put("expiresAt", "2026-08-25");
        vars.put("deepLink", "https://careers.acme.example/careers/intern/offers/x");
        var r = renderSeeded("OFFER_EXTENDED", vars);
        assertRenderClean(r, "OFFER_EXTENDED");
        assertTrue(r.subject().contains("Software Engineer"));
        assertTrue(r.subject().contains("Acme Tech Staffing LLC"));
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("85000 USD / annual"));
        assertTrue(r.body().contains("2026-09-01"));
        assertTrue(r.body().contains("2026-08-25"));
    }

    @Test
    void offer_extended_handles_missing_compensation_via_fallback_string() {
        // Wiring supplies "See offer letter" when comp amount is null.
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("jobTitle", "Intern");
        vars.put("entityName", "Acme");
        vars.put("compensationLine", "See offer letter");
        vars.put("startDate", "TBD");
        vars.put("expiresAt", "See offer letter");
        vars.put("deepLink", "https://x/offer");
        var r = renderSeeded("OFFER_EXTENDED", vars);
        assertRenderClean(r, "OFFER_EXTENDED (missing comp)");
        assertTrue(r.body().contains("See offer letter"));
    }

    @Test
    void everify_case_opened_renders_with_wiring_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        var r = renderSeeded("EVERIFY_CASE_OPENED", vars);
        assertRenderClean(r, "EVERIFY_CASE_OPENED");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("E-Verify"));
    }

    @Test
    void everify_tnc_renders_with_legacy_wiring_fallback_erm() {
        // Legacy path doesn't have ERM context — wiring passes "your ERM".
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("ermName", "your ERM");
        var r = renderSeeded("EVERIFY_TENTATIVE_NONCONFIRMATION", vars);
        assertRenderClean(r, "EVERIFY_TENTATIVE_NONCONFIRMATION");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("your ERM"));
        assertTrue(r.body().contains("Tentative Nonconfirmation"));
    }

    @Test
    void everify_authorized_renders_with_wiring_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        var r = renderSeeded("EVERIFY_AUTHORIZED", vars);
        assertRenderClean(r, "EVERIFY_AUTHORIZED");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("Employment Authorized"));
    }

    @Test
    void weekly_report_returned_renders_with_review_notes() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("weekStart", "2026-08-10");
        vars.put("reviewNotesLine",
                "\n\nReviewer notes: Add detail to section 3 metrics.");
        vars.put("deepLink",
                "https://careers.acme.example/careers/intern/weekly-meetings");
        var r = renderSeeded("WEEKLY_REPORT_RETURNED", vars);
        assertRenderClean(r, "WEEKLY_REPORT_RETURNED");
        assertTrue(r.subject().contains("2026-08-10"));
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("Add detail to section 3 metrics"));
    }

    @Test
    void weekly_report_returned_renders_cleanly_without_review_notes() {
        // Wiring passes empty reviewNotesLine when reviewer left no notes.
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("weekStart", "2026-08-10");
        vars.put("reviewNotesLine", "");
        vars.put("deepLink", "https://x/wk");
        var r = renderSeeded("WEEKLY_REPORT_RETURNED", vars);
        assertRenderClean(r, "WEEKLY_REPORT_RETURNED (no notes)");
        assertFalse(r.body().contains("Reviewer notes:"),
                "no 'Reviewer notes:' label when the block is empty");
    }

    @Test
    void weekly_report_approved_renders_with_wiring_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("weekStart", "2026-08-10");
        vars.put("deepLink", "https://x/wk");
        var r = renderSeeded("WEEKLY_REPORT_APPROVED", vars);
        assertRenderClean(r, "WEEKLY_REPORT_APPROVED");
        assertTrue(r.subject().contains("2026-08-10"));
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("approved"));
    }

    @Test
    void i9_section1_reminder_renders_with_wiring_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("dueDate", "2026-08-25");
        vars.put("deepLink",
                "https://careers.acme.example/careers/intern/documents");
        var r = renderSeeded("I9_SECTION1_REMINDER", vars);
        assertRenderClean(r, "I9_SECTION1_REMINDER");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("2026-08-25"));
        assertTrue(r.body().contains("I-9"));
    }

    // ── Rebrand-safety cross-check on 4 new seeds ─────────────────────

    @Test
    void slice6d_new_seeds_have_no_literal_anvi_after_brandify() {
        for (String key : List.of("OFFER_EXTENDED", "WEEKLY_REPORT_RETURNED",
                "WEEKLY_REPORT_APPROVED", "I9_SECTION1_REMINDER")) {
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

    // ── Grep-proof: NotificationService renders all 8 templates ───────

    @Test
    void notification_service_renders_all_eight_slice6d_templates() throws IOException {
        String source = readSource(
                "apps/backend/src/main/java/com/anvicorp/api/notification/"
                        + "NotificationService.java");
        String stripped = source.replaceAll("\\s+", "");
        for (String key : List.of("INTERVIEW_SCHEDULED", "OFFER_EXTENDED",
                "EVERIFY_CASE_OPENED", "EVERIFY_TENTATIVE_NONCONFIRMATION",
                "EVERIFY_AUTHORIZED", "WEEKLY_REPORT_RETURNED",
                "WEEKLY_REPORT_APPROVED", "I9_SECTION1_REMINDER")) {
            assertTrue(stripped.contains("templateService.render(\"" + key + "\""),
                    "NotificationService must render template " + key
                            + " (Slice-6d template-first invariant).");
        }
    }

    /** Fallback discipline — the 8 typed emailProvider.sendXxx methods
     *  must still be called (as fallback branches when the template row
     *  is absent). Backward-safe. */
    @Test
    void notification_service_retains_typed_fallback_for_each_migrated_method() throws IOException {
        String source = readSource(
                "apps/backend/src/main/java/com/anvicorp/api/notification/"
                        + "NotificationService.java");
        for (String typed : List.of(
                "emailProvider.sendInterviewScheduled",
                "emailProvider.sendOfferExtended",
                "emailProvider.sendEVerifyCaseOpened",
                "emailProvider.sendEVerifyTncAlert",
                "emailProvider.sendEVerifyCleared",
                "emailProvider.sendWeeklyReportReturned",
                "emailProvider.sendWeeklyReportApproved",
                "emailProvider.sendI9Section1Reminder")) {
            assertTrue(source.contains(typed),
                    "NotificationService must retain " + typed
                            + " as fallback for backward-safe delivery.");
        }
    }

    /** Rebrand-safe — no literal "Anvi Corp" in the wiring paths. */
    @Test
    void notification_service_has_no_literal_anvi_corp_string() throws IOException {
        String source = readSource(
                "apps/backend/src/main/java/com/anvicorp/api/notification/"
                        + "NotificationService.java");
        assertFalse(source.contains("\"Anvi Corp\""),
                "NotificationService embeds literal \"Anvi Corp\" — "
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
