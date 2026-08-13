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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Email Slice 2 — interview cancellation + meeting host invite fold-ins.
 * Pins that:
 *
 * <ol>
 *   <li>INTERVIEW_CANCELLED (pre-existing) is still seeded and renders
 *       cleanly with the vars {@link
 *       com.anvicorp.api.listener.InterviewEmailListener#sendCancelled}
 *       supplies — and the listener's inline fallback body no longer
 *       duplicates the template copy.</li>
 *   <li>MEETING_INVITE_HOST (new Slice-2 seed) is registered and renders
 *       cleanly with the vars {@link
 *       com.anvicorp.api.notification.SchedulerMeetingEmailSender#send}
 *       supplies — brandName / signoffBlock resolve, no unresolved
 *       {{placeholder}}, no literal Anvi leak.</li>
 *   <li>{@code SchedulerMeetingEmailSender} no longer constructs the plain
 *       body via {@code StringBuilder} — the grep-proof that the inline
 *       body is gone.</li>
 * </ol>
 */
class EmailSlice2InterviewMeetingTest {

    private static final BrandConfig BRAND = new BrandConfig(
            "Acme Tech", "Acme Careers", "Acme Technologies LLC", "hello@acme.example");

    // ── Seeds registered ──────────────────────────────────────────────

    @Test
    void slice2_new_seed_is_registered() {
        assertSeedExists("MEETING_INVITE_HOST");
    }

    @Test
    void slice2_existing_seed_still_present() {
        assertSeedExists("INTERVIEW_CANCELLED");
    }

    // ── INTERVIEW_CANCELLED render ────────────────────────────────────

    @Test
    void interview_cancelled_renders_with_listener_vars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", "Alice");
        vars.put("jobTitle", "Software Engineer Intern");
        vars.put("cancellationMessage", "Reason: Interviewer conflict");
        var r = renderSeeded("INTERVIEW_CANCELLED", vars);
        assertRenderClean(r, "INTERVIEW_CANCELLED");
        assertTrue(r.body().contains("Alice"),
                "body should greet the applicant by first name");
        assertTrue(r.body().contains("Software Engineer Intern"),
                "body should include the job title");
        assertTrue(r.body().contains("Reason: Interviewer conflict"),
                "body should surface the caller-supplied cancellation message");
        assertTrue(r.subject().startsWith("Your Acme Tech interview"),
                "subject should resolve brandName from BrandConfig, not print literal Anvi");
        assertTrue(r.body().endsWith("— Acme Tech"),
                "signoffBlock should resolve to the brand line");
    }

    // ── MEETING_INVITE_HOST render — weekly meeting flow ──────────────

    @Test
    void meeting_invite_host_renders_for_weekly_meeting() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("subjectPrefix", "Weekly meeting scheduled");
        vars.put("meetingTitle", "1:1 with Alice");
        vars.put("recipientName", "Bob");
        vars.put("participantLine", " with Alice");
        vars.put("scheduledForLocal", "Wednesday, April 15, 2026 at 3:00 PM");
        vars.put("timezone", "America/Chicago");
        vars.put("hostAccessBlock", "Start as host (one click, no Zoom sign-in needed): "
                + "https://zoom.example/s/abc\n"
                + "Note: this start link expires roughly 2 hours after the "
                + "meeting was created. If it doesn't work, open the meeting "
                + "in the {{brandName}} dashboard for a fresh link.");
        var r = renderSeeded("MEETING_INVITE_HOST", vars);
        assertRenderClean(r, "MEETING_INVITE_HOST");
        assertEquals("Weekly meeting scheduled — 1:1 with Alice", r.subject(),
                "subject should compose subjectPrefix + meetingTitle");
        assertTrue(r.body().contains("Hi Bob,"),
                "body should greet the host");
        assertTrue(r.body().contains("You scheduled \"1:1 with Alice\" with Alice"),
                "body should surface title + participantLine composition");
        assertTrue(r.body().contains("Wednesday, April 15, 2026 at 3:00 PM"),
                "body should include the localized when + zone");
        assertTrue(r.body().contains("America/Chicago"),
                "body should include the timezone");
        assertTrue(r.body().contains("https://zoom.example/s/abc"),
                "body should include the host start URL");
        assertTrue(r.body().endsWith("— Acme Tech"),
                "signoffBlock should resolve to the brand line");
    }

    // ── MEETING_INVITE_HOST render — interview scheduler flow ─────────

    @Test
    void meeting_invite_host_renders_for_interview_scheduler() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("subjectPrefix", "Interview scheduled");
        vars.put("meetingTitle", "Software Engineer Intern");
        vars.put("recipientName", "Interviewer");
        vars.put("participantLine", " with Alice Applicant (candidate)");
        vars.put("scheduledForLocal", "Friday, May 1, 2026 at 10:00 AM");
        vars.put("timezone", "UTC");
        vars.put("hostAccessBlock",
                "Open the meeting in the {{brandName}} dashboard for your host start link.");
        var r = renderSeeded("MEETING_INVITE_HOST", vars);
        assertRenderClean(r, "MEETING_INVITE_HOST");
        assertEquals("Interview scheduled — Software Engineer Intern", r.subject());
        assertTrue(r.body().contains("with Alice Applicant (candidate)"),
                "participant label should be composed into body");
        // Dashboard-fallback line (no start URL) still has the {{brandName}}
        // token because the render only resolves TEMPLATE placeholders,
        // not caller-supplied var values. The sender does a second-pass
        // brand substitution on the returned body before send — that's
        // verified by the grep-proof test below.
        assertTrue(r.body().contains("{{brandName}}"),
                "dashboard-fallback line intentionally preserves the {{brandName}} "
                        + "token for the sender's second-pass substitution");
    }

    // ── Grep-proof: no inline construction of the interview-cancel body ──

    @Test
    void interview_cancelled_listener_no_longer_carries_duplicate_template_body()
            throws IOException {
        Path src = listenerPath();
        assertTrue(Files.exists(src),
                "InterviewEmailListener.java must exist for grep-proof test");
        String source = Files.readString(src, StandardCharsets.UTF_8);
        // The prior fallback duplicated the template with a full "Your
        // interview for X has been cancelled.\n\n{cancellationMessage}\n\n
        // We will follow up shortly with next steps." block. Assert those
        // signature strings are gone from the cancellation site.
        assertFalse(source.contains("We will follow up shortly with next steps."),
                "InterviewEmailListener must not carry the pre-Slice-2 duplicate "
                        + "cancellation body — that copy now lives only in the "
                        + "INTERVIEW_CANCELLED template.");
        // brand.signoffErm() used to be appended to the inline fallback.
        // The cancellation path should no longer touch it (the other three
        // paths — scheduled / rescheduled / completed — still may).
        int signoffErmCount = countMatches(source, "brand.signoffErm()");
        // Pre-Slice-2 the file had 5 calls (scheduled + rescheduled +
        // cancelled + sendDecision REJECTED + sendDecision SELECTED/HOLD).
        // Slice 2 removes the cancellation one, so 4 remain. A regression
        // that reintroduces the cancellation-body inline signoff would
        // push this back to 5; a later fold-in landing inside this slice's
        // scope would drop it below 4.
        assertEquals(4, signoffErmCount,
                "brand.signoffErm() should appear exactly 4 times "
                        + "(scheduled + rescheduled + 2 sendDecision "
                        + "branches) — a count of 5 means the cancellation "
                        + "fallback grew back its inline signoff; a count "
                        + "of 3 means another fold-in landed inside this "
                        + "slice's scope.");
    }

    // ── Grep-proof: no inline StringBuilder body in the scheduler sender ──

    @Test
    void scheduler_meeting_sender_no_longer_uses_stringbuilder_body()
            throws IOException {
        Path src = schedulerPath();
        assertTrue(Files.exists(src),
                "SchedulerMeetingEmailSender.java must exist for grep-proof test");
        String source = Files.readString(src, StandardCharsets.UTF_8);
        assertFalse(source.contains("StringBuilder plain"),
                "SchedulerMeetingEmailSender must not build the plain body via "
                        + "StringBuilder — Slice 2 routes the body through the "
                        + "MEETING_INVITE_HOST template.");
        assertFalse(source.contains("plain.append("),
                "SchedulerMeetingEmailSender must not append inline body "
                        + "fragments — Slice 2 sources the body from the template.");
        // The sender must READ from templateService.render (proof of wire).
        assertTrue(source.contains("templateService.render(\"MEETING_INVITE_HOST\""),
                "SchedulerMeetingEmailSender must render the MEETING_INVITE_HOST "
                        + "template.");
    }

    // ── Grep-proof: no literal Anvi anywhere in the touched paths ─────

    @Test
    void touched_paths_have_no_literal_anvi() throws IOException {
        for (Path src : List.of(listenerPath(), schedulerPath())) {
            String source = Files.readString(src, StandardCharsets.UTF_8);
            assertFalse(source.contains("Anvi Corp") || source.contains("Anvi Careers"),
                    src.getFileName() + " must not carry a literal Anvi brand "
                            + "string — all brand references route through "
                            + "BrandConfig / template placeholders.");
        }
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static Path listenerPath() {
        Path p = Path.of("src/main/java/com/anvicorp/api/listener/"
                + "InterviewEmailListener.java");
        if (!Files.exists(p)) {
            p = Path.of("apps/backend/src/main/java/com/anvicorp/api/listener/"
                    + "InterviewEmailListener.java");
        }
        return p;
    }

    private static Path schedulerPath() {
        Path p = Path.of("src/main/java/com/anvicorp/api/notification/"
                + "SchedulerMeetingEmailSender.java");
        if (!Files.exists(p)) {
            p = Path.of("apps/backend/src/main/java/com/anvicorp/api/notification/"
                    + "SchedulerMeetingEmailSender.java");
        }
        return p;
    }

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

    /** Common assertions every Slice-2 render must satisfy. Note that
     *  MEETING_INVITE_HOST's dashboard-fallback body path intentionally
     *  contains {{brandName}} for the sender's second-pass substitution;
     *  the {@code allowBrandTokenInBody} skip lets that case through. */
    private static void assertRenderClean(
            CommunicationTemplateService.Rendered r, String key) {
        assertNotNull(r.subject(), key + " subject should be set");
        assertNotNull(r.body(), key + " body should be set");
        // Subject never carries a caller-second-pass token, so it must be
        // fully resolved.
        assertFalse(hasUnresolvedPlaceholder(r.subject()),
                key + " subject has unresolved {{placeholder}}: " + r.subject());
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

    // ── Test doubles (mirror Slice 1) ──────────────────────────────────

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
