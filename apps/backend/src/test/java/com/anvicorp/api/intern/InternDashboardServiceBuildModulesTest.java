package com.anvicorp.api.intern;

import com.anvicorp.api.enums.InternLifecycleStatus;
import com.anvicorp.api.intern.InternDashboardResponse.ModuleState;
import com.anvicorp.api.intern.InternDashboardResponse.Modules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave-2 #4 — pins the progressive-reveal matrix for every lifecycle
 * status. The frontend's role-aware sidebar and route guard both derive
 * their behaviour from this map, so the visibility contract here IS the
 * user-visible spec: a fresh intern at EMAIL_VERIFIED must see only Home /
 * Job Postings / Messages / Help (Profile is always visible from the
 * sidebar side — no moduleKey — so it is not represented here).
 *
 * <p>If a lifecycle stage's visibility set changes, update the assertion
 * intentionally — the test guards against accidental regressions where a
 * sidebar section reappears too early or too late.</p>
 */
class InternDashboardServiceBuildModulesTest {

    // Helpers ─────────────────────────────────────────────────────────────
    private static Modules modulesFor(InternLifecycleStatus s) {
        return InternDashboardService.buildModules(
                s, InternDashboardService.deriveMode(s));
    }

    private static void assertVisible(String key, ModuleState state) {
        assertNotNull(state, key + " must not be null");
        assertTrue(state.visible(), key + " must be visible");
    }

    private static void assertHidden(String key, ModuleState state) {
        assertNotNull(state, key + " must not be null");
        assertFalse(state.visible(), key + " must be hidden");
    }

    private static void assertReadOnly(String key, ModuleState state) {
        assertVisible(key, state);
        assertTrue(state.readOnly(), key + " must be read-only");
    }

    // Wave-2 #4 fresh-intern spec ─────────────────────────────────────────

    @Test
    void email_verified_shows_only_starter_five() {
        Modules m = modulesFor(InternLifecycleStatus.EMAIL_VERIFIED);

        // Starter set — always visible, never locked.
        assertVisible("home", m.home());
        assertVisible("jobPostings", m.jobPostings());
        assertVisible("messages", m.messages());
        assertVisible("help", m.help());
        assertFalse(m.jobPostings().locked(), "jobPostings must be unlocked");
        assertFalse(m.messages().locked(), "messages must be unlocked");

        // Everything else — hidden, not locked-visible.
        assertHidden("myApplications", m.myApplications());
        assertHidden("interviewCenter", m.interviewCenter());
        assertHidden("offerLetter", m.offerLetter());
        assertHidden("onboarding", m.onboarding());
        assertHidden("myProjects", m.myProjects());
        assertHidden("timesheets", m.timesheets());
        assertHidden("evaluations", m.evaluations());
        assertHidden("documents", m.documents());
        assertHidden("doubts", m.doubts());
    }

    @Test
    void registered_matches_email_verified_starter_set() {
        // Pre-email-verified interns get the same starter set. If future
        // waves add an email-verification gate that changes this, update
        // this test intentionally.
        Modules m = modulesFor(InternLifecycleStatus.REGISTERED);
        assertVisible("home", m.home());
        assertVisible("jobPostings", m.jobPostings());
        assertVisible("messages", m.messages());
        assertVisible("help", m.help());
        assertHidden("myApplications", m.myApplications());
        assertHidden("interviewCenter", m.interviewCenter());
        assertHidden("offerLetter", m.offerLetter());
        assertHidden("documents", m.documents());
    }

    // Wave-2 #4 progressive-reveal spec ───────────────────────────────────

    @Test
    void application_submitted_reveals_my_applications_only() {
        Modules m = modulesFor(InternLifecycleStatus.APPLICATION_SUBMITTED);
        assertVisible("myApplications", m.myApplications());
        assertFalse(m.myApplications().readOnly(),
                "myApplications must be interactive while APPLICANT");
        assertVisible("jobPostings", m.jobPostings());
        assertHidden("interviewCenter", m.interviewCenter());
        assertHidden("offerLetter", m.offerLetter());
    }

    @Test
    void shortlisted_reveals_interview_center() {
        Modules m = modulesFor(InternLifecycleStatus.SHORTLISTED);
        assertVisible("myApplications", m.myApplications());
        assertVisible("interviewCenter", m.interviewCenter());
        assertHidden("offerLetter", m.offerLetter());
        assertHidden("documents", m.documents());
    }

    @Test
    void interview_scheduled_and_completed_keep_interview_visible() {
        for (InternLifecycleStatus s : new InternLifecycleStatus[]{
                InternLifecycleStatus.INTERVIEW_SCHEDULED,
                InternLifecycleStatus.INTERVIEW_COMPLETED}) {
            Modules m = modulesFor(s);
            assertVisible(s + " interviewCenter", m.interviewCenter());
            assertFalse(m.interviewCenter().readOnly(),
                    s + " interviewCenter must be interactive");
            assertHidden(s + " offerLetter", m.offerLetter());
        }
    }

    @Test
    void offer_sent_reveals_offer_letter_and_read_onlies_prior_apps() {
        Modules m = modulesFor(InternLifecycleStatus.OFFER_SENT);
        assertVisible("offerLetter", m.offerLetter());
        assertFalse(m.offerLetter().readOnly(),
                "offerLetter must be interactive while OFFER");
        assertReadOnly("myApplications", m.myApplications());
        assertReadOnly("interviewCenter", m.interviewCenter());
        assertHidden("documents", m.documents());
        assertHidden("onboarding", m.onboarding());
    }

    @Test
    void offer_signed_reveals_documents_and_onboarding_and_read_onlies_offer() {
        Modules m = modulesFor(InternLifecycleStatus.OFFER_SIGNED);
        assertVisible("onboarding", m.onboarding());
        assertVisible("documents", m.documents());
        assertReadOnly("offerLetter", m.offerLetter());
        assertHidden("myProjects", m.myProjects());
        assertHidden("timesheets", m.timesheets());
    }

    @Test
    void active_intern_swaps_to_work_workspace() {
        Modules m = modulesFor(InternLifecycleStatus.ACTIVE_INTERN);
        // Work modules live now.
        assertVisible("myProjects", m.myProjects());
        assertVisible("timesheets", m.timesheets());
        assertVisible("evaluations", m.evaluations());
        assertVisible("doubts", m.doubts());
        assertVisible("documents", m.documents());
        assertVisible("messages", m.messages());
        // Application-phase modules — hidden.
        assertHidden("jobPostings", m.jobPostings());
        assertHidden("myApplications", m.myApplications());
        assertHidden("interviewCenter", m.interviewCenter());
        assertHidden("offerLetter", m.offerLetter());
        assertHidden("onboarding", m.onboarding());
    }

    @Test
    void inactive_intern_read_onlies_work_modules_and_locks_messages() {
        Modules m = modulesFor(InternLifecycleStatus.INACTIVE_INTERN);
        assertReadOnly("myProjects", m.myProjects());
        assertReadOnly("timesheets", m.timesheets());
        assertReadOnly("evaluations", m.evaluations());
        assertReadOnly("doubts", m.doubts());
        assertReadOnly("documents", m.documents());
        assertReadOnly("home", m.home());
        // Messages stays visible but locks — history is browsable, no
        // new composition. Wave-2 pin for the terminal state.
        assertVisible("messages", m.messages());
        assertTrue(m.messages().locked(),
                "messages must lock for INACTIVE interns");
        // Application-phase modules stay hidden.
        assertHidden("jobPostings", m.jobPostings());
        assertHidden("offerLetter", m.offerLetter());
    }

    // Wave-2 #10 route-guard invariants ───────────────────────────────────

    @Test
    void locked_or_hidden_at_email_verified_covers_all_gated_modules() {
        // The frontend InternModuleRouteGuard redirects on both visible=false
        // AND locked=true. This test pins that at the starter stage every
        // module that isn't part of the starter five is EITHER hidden OR
        // locked — never accidentally interactable.
        Modules m = modulesFor(InternLifecycleStatus.EMAIL_VERIFIED);
        for (ModuleState state : new ModuleState[]{
                m.myApplications(), m.interviewCenter(), m.offerLetter(),
                m.onboarding(), m.myProjects(), m.timesheets(),
                m.evaluations(), m.documents(), m.doubts()}) {
            assertTrue(!state.visible() || state.locked(),
                    "module must be non-interactive at EMAIL_VERIFIED (visible=false or locked=true)");
        }
    }

    // Regression pin: deriveMode ordering ─────────────────────────────────

    @Test
    void derive_mode_matches_documented_mapping() {
        assertEquals("APPLICANT", InternDashboardService.deriveMode(InternLifecycleStatus.REGISTERED));
        assertEquals("APPLICANT", InternDashboardService.deriveMode(InternLifecycleStatus.EMAIL_VERIFIED));
        assertEquals("APPLICANT", InternDashboardService.deriveMode(InternLifecycleStatus.APPLICATION_SUBMITTED));
        assertEquals("INTERVIEW", InternDashboardService.deriveMode(InternLifecycleStatus.SHORTLISTED));
        assertEquals("INTERVIEW", InternDashboardService.deriveMode(InternLifecycleStatus.INTERVIEW_SCHEDULED));
        assertEquals("INTERVIEW", InternDashboardService.deriveMode(InternLifecycleStatus.INTERVIEW_COMPLETED));
        assertEquals("OFFER",     InternDashboardService.deriveMode(InternLifecycleStatus.OFFER_SENT));
        assertEquals("NEW_HIRE",  InternDashboardService.deriveMode(InternLifecycleStatus.OFFER_SIGNED));
        assertEquals("NEW_HIRE",  InternDashboardService.deriveMode(InternLifecycleStatus.EMPLOYEE_ID_CREATED));
        assertEquals("NEW_HIRE",  InternDashboardService.deriveMode(InternLifecycleStatus.ONBOARDING_ASSIGNED));
        assertEquals("ACTIVE_INTERN", InternDashboardService.deriveMode(InternLifecycleStatus.ONBOARDING_ACCEPTED));
        assertEquals("ACTIVE_INTERN", InternDashboardService.deriveMode(InternLifecycleStatus.ACTIVE_INTERN));
        assertEquals("INACTIVE",  InternDashboardService.deriveMode(InternLifecycleStatus.INACTIVE_INTERN));
    }
}
