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
 * Email Slice 6b — Category A high-priority subset fold-ins.
 *
 * <p>Six NotificationService legacy typed methods migrated to
 * template-first with the existing {@code emailProvider.sendXxx}
 * typed hardcoded method as a rebrand-safe fallback (matching the
 * shape already used by APPLICATION_RECEIVED / OFFER_ACCEPTED /
 * I983_SELF_EVAL_DUE in the same file):</p>
 *
 * <ol>
 *   <li>{@code sendOnboardingWelcome} → new {@code ONBOARDING_WELCOME}
 *       template.</li>
 *   <li>{@code sendInterviewReminder} → new {@code INTERVIEW_REMINDER}
 *       template.</li>
 *   <li>{@code sendComplianceTaskReminder} → new
 *       {@code COMPLIANCE_TASK_REMINDER} template.</li>
 *   <li>{@code sendWorkAuthExpiryReminder} → REUSES the existing
 *       {@code WORK_AUTH_EXPIRING} template (also used by
 *       ComplianceLifecycleListener's event-driven path).</li>
 *   <li>{@code sendTimesheetDue} → new {@code TIMESHEET_DUE}
 *       template.</li>
 *   <li>{@code sendWeeklyReportDue} → new {@code WEEKLY_REPORT_DUE}
 *       template.</li>
 * </ol>
 *
 * <p>Skipped from the survey subset (deferred to a dedicated dedup
 * investigation): {@code sendApplicationShortlisted} and
 * {@code sendApplicationRejected}. Both potentially fire alongside
 * ApplicationDecisionListener's templated
 * {@code APPLICATION_SHORTLIST} / {@code APPLICATION_REJECT} legs
 * (different event-type strings so no in-app dedupe) — migrating to
 * template-first without deduping would perpetuate a potential double-
 * send. Reported in the slice summary.</p>
 */
class EmailSlice6bCategoryASubsetTest {

    private static final BrandConfig BRAND = new BrandConfig(
            "Acme Tech", "Acme Careers", "Acme Technologies LLC", "hello@acme.example");

    // ── All 6 templates registered (5 new + 1 reused) ─────────────────

    @Test
    void slice6b_templates_are_all_registered() {
        assertSeedExists("ONBOARDING_WELCOME");
        assertSeedExists("INTERVIEW_REMINDER");
        assertSeedExists("COMPLIANCE_TASK_REMINDER");
        assertSeedExists("TIMESHEET_DUE");
        assertSeedExists("WEEKLY_REPORT_DUE");
        // Reused template — must remain present.
        assertSeedExists("WORK_AUTH_EXPIRING");
    }

    // ── Per-template render smoke tests (wiring-shaped var maps) ──────

    @Test
    void onboarding_welcome_renders_with_wiring_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("jobTitle", "Software Engineering Intern");
        vars.put("entityName", "Acme Tech Staffing LLC");
        vars.put("startDate", "2026-09-01");
        vars.put("deepLink",
                "https://careers.acme.example/careers/intern/documents");
        var r = renderSeeded("ONBOARDING_WELCOME", vars);
        assertRenderClean(r, "ONBOARDING_WELCOME");
        assertTrue(r.subject().contains("Software Engineering Intern"),
                "subject must name the role");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("Acme Tech Staffing LLC"));
        assertTrue(r.body().contains("Software Engineering Intern"));
        assertTrue(r.body().contains("2026-09-01"),
                "body must show the start date");
    }

    @Test
    void onboarding_welcome_handles_null_start_date_via_wiring_fallback() {
        // The wiring supplies "your scheduled start date" when
        // engagement.getActualStartDate() is null.
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("jobTitle", "Intern");
        vars.put("entityName", "Acme");
        vars.put("startDate", "your scheduled start date");
        vars.put("deepLink", "https://x/dash");
        var r = renderSeeded("ONBOARDING_WELCOME", vars);
        assertRenderClean(r, "ONBOARDING_WELCOME (null start)");
        assertTrue(r.body().contains("your scheduled start date"));
    }

    @Test
    void interview_reminder_renders_with_wiring_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("jobTitle", "Backend Intern");
        vars.put("entityName", "Acme Tech");
        vars.put("scheduledAtLocal", "2026-08-15T15:30:00Z");
        vars.put("durationMinutes", 45);
        vars.put("interviewType", "TECHNICAL");
        vars.put("interviewerName", "Bob Interviewer");
        vars.put("meetingUrl", "https://zoom.example/j/123456");
        var r = renderSeeded("INTERVIEW_REMINDER", vars);
        assertRenderClean(r, "INTERVIEW_REMINDER");
        assertTrue(r.subject().contains("Backend Intern"));
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("Bob Interviewer"));
        assertTrue(r.body().contains("TECHNICAL"));
        assertTrue(r.body().contains("45"),
                "body must show duration");
        assertTrue(r.body().contains("https://zoom.example/j/123456"));
    }

    @Test
    void compliance_task_reminder_renders_with_overdue_line() {
        // Populated overdueLine — task was overdue, wiring computed
        // "  · Overdue by N day(s)".
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("taskTitle", "Complete I-9 Form");
        vars.put("dueDate", "2026-07-15");
        vars.put("overdueLine", "  · Overdue by 5 days");
        vars.put("deepLink", "https://x/dash");
        var r = renderSeeded("COMPLIANCE_TASK_REMINDER", vars);
        assertRenderClean(r, "COMPLIANCE_TASK_REMINDER (overdue)");
        assertTrue(r.subject().contains("Complete I-9 Form"));
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("Overdue by 5 days"));
    }

    @Test
    void compliance_task_reminder_renders_cleanly_when_not_overdue() {
        // Empty overdueLine — task due today, no orphan "Overdue by …"
        // line leaked into the render.
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("taskTitle", "Direct Deposit Form");
        vars.put("dueDate", "2026-08-14");
        vars.put("overdueLine", "");
        vars.put("deepLink", "https://x/dash");
        var r = renderSeeded("COMPLIANCE_TASK_REMINDER", vars);
        assertRenderClean(r, "COMPLIANCE_TASK_REMINDER (not overdue)");
        assertFalse(r.body().contains("Overdue"),
                "no 'Overdue' text should appear when overdueLine is empty");
    }

    @Test
    void work_auth_expiring_renders_with_scheduler_vars() {
        // The scheduler wiring reuses the existing WORK_AUTH_EXPIRING
        // template. It doesn't have ERM context so passes "your ERM"
        // as the fallback ermName — must render coherently.
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("workAuthType", "STEM OPT");
        vars.put("expirationDate", "2026-11-30");
        vars.put("daysUntilExpiration", 30);
        vars.put("ermName", "your ERM");
        var r = renderSeeded("WORK_AUTH_EXPIRING", vars);
        assertRenderClean(r, "WORK_AUTH_EXPIRING (scheduler)");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("STEM OPT"));
        assertTrue(r.body().contains("2026-11-30"));
        assertTrue(r.body().contains("30"));
        assertTrue(r.body().contains("your ERM"));
    }

    @Test
    void timesheet_due_renders_with_wiring_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("weekStart", "2026-08-10");
        vars.put("deepLink",
                "https://careers.acme.example/careers/intern/timesheets");
        var r = renderSeeded("TIMESHEET_DUE", vars);
        assertRenderClean(r, "TIMESHEET_DUE");
        assertTrue(r.subject().contains("2026-08-10"),
                "subject must show the week start");
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("2026-08-10"));
        assertTrue(r.body().contains(
                "https://careers.acme.example/careers/intern/timesheets"));
    }

    @Test
    void weekly_report_due_renders_with_wiring_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("weekStart", "2026-08-10");
        vars.put("deepLink",
                "https://careers.acme.example/careers/intern/weekly-meetings");
        var r = renderSeeded("WEEKLY_REPORT_DUE", vars);
        assertRenderClean(r, "WEEKLY_REPORT_DUE");
        assertTrue(r.subject().contains("2026-08-10"));
        assertTrue(r.body().contains("Alice"));
        assertTrue(r.body().contains("2026-08-10"));
    }

    // ── Rebrand-safety cross-check ────────────────────────────────────

    @Test
    void slice6b_new_seeds_have_no_literal_anvi_after_brandify() {
        for (String key : List.of("ONBOARDING_WELCOME", "INTERVIEW_REMINDER",
                "COMPLIANCE_TASK_REMINDER", "TIMESHEET_DUE", "WEEKLY_REPORT_DUE")) {
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

    // ── Grep-proof: NotificationService uses template-first pattern ───

    /** After Slice 6b, the 6 targeted deliver-blocks in
     *  {@code NotificationService} route through
     *  {@code templateService.render(...)} before falling back to the
     *  typed {@code emailProvider.sendXxx}. Presence of the six
     *  template-key strings inside a render call is the invariant. */
    @Test
    void notification_service_renders_all_six_slice6b_templates() throws IOException {
        String source = readSource(
                "apps/backend/src/main/java/com/anvicorp/api/notification/"
                        + "NotificationService.java");
        String collapsed = source.replaceAll("\\s+", " ");
        for (String key : List.of("ONBOARDING_WELCOME", "INTERVIEW_REMINDER",
                "COMPLIANCE_TASK_REMINDER", "WORK_AUTH_EXPIRING",
                "TIMESHEET_DUE", "WEEKLY_REPORT_DUE")) {
            assertTrue(collapsed.contains("templateService.render( \"" + key + "\"")
                            || collapsed.contains("templateService.render(\"" + key + "\""),
                    "NotificationService must render template " + key
                            + " (template-first pattern).");
        }
    }

    /** Fallback discipline — for each Slice-6b template, the typed
     *  {@code emailProvider.sendXxx(...)} method is still called (as
     *  the fallback branch when the template row is absent). This is
     *  what makes the migration backward-safe: a pre-seed deploy or an
     *  operator-deleted template row still sends the email via the
     *  legacy typed path. */
    @Test
    void notification_service_retains_typed_fallback_for_each_migrated_method() throws IOException {
        String source = readSource(
                "apps/backend/src/main/java/com/anvicorp/api/notification/"
                        + "NotificationService.java");
        for (String typedMethod : List.of(
                "emailProvider.sendOnboardingWelcome",
                "emailProvider.sendInterviewReminder",
                "emailProvider.sendComplianceTaskReminder",
                "emailProvider.sendWorkAuthExpiryReminder",
                "emailProvider.sendTimesheetDue",
                "emailProvider.sendWeeklyReportDue")) {
            assertTrue(source.contains(typedMethod),
                    "NotificationService must retain " + typedMethod
                            + " as fallback for backward-safe delivery.");
        }
    }

    /** Rebrand-safe — the NotificationService file must not embed any
     *  literal {@code "Anvi Corp"} string in the wiring paths. Brand
     *  tokens flow via the seeded templates (brandify at seed time). */
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
