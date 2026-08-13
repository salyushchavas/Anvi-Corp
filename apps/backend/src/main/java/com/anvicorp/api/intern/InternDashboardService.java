package com.anvicorp.api.intern;

import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.Interview;
import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.InternLifecycleStatus;
import com.anvicorp.api.enums.OfferStatus;
import com.anvicorp.api.erm.offer.SelectionAckPolicy;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.OfferRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.service.ExitService;
import com.anvicorp.api.service.ProfileCompletionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.anvicorp.api.intern.InternDashboardResponse.*;

/**
 * Derives the intern dashboard mode and the full module / stepper /
 * next-action payload from {@link User#getLifecycleStatus()}. Single source
 * of truth — controllers and frontend pull from one shape so the doc
 * matrix is implemented exactly once.
 *
 * <p>Pure function of the user's lifecycle state; no DB writes. Future
 * phases will extend the contacts panel once {@code InternLifecycle} is
 * populated.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InternDashboardService {

    private final InternLifecycleRepository internLifecycleRepository;
    private final UserRepository userRepository;
    private final InternEvaluationService internEvaluationService;
    private final ExitService exitService;
    private final OfferRepository offerRepository;
    private final ApplicationRepository applicationRepository;
    private final SelectionAckPolicy selectionAckPolicy;
    private final OrgTeamResolver orgTeamResolver;
    private final ProfileCompletionService profileCompletionService;
    // Pipeline-restore — surface a "review + sign your offer letter" card
    // whenever the intern has a live offer-family IDMS instance
    // (SENT_TO_INTERN or RETURNED). Trumps the lifecycle-status-driven
    // OFFER_SENT branch below so the card links at the new IDMS page
    // instead of the retiring legacy /careers/intern/offer route.
    private final com.anvicorp.api.erm.idms.DocumentInstanceRepository
            documentInstanceRepository;

    /**
     * Read-only transaction so lazy associations the dashboard touches
     * resolve cleanly. Specifically: {@code Application.jobPosting} is
     * {@code @ManyToOne(LAZY)} and the selection-context picker reads
     * {@code app.getJobPosting().getTitle()}; without an open session
     * that access throws LazyInitializationException, the catch returns
     * null, and the SELECTED candidate falls through to "Awaiting
     * interview feedback". Other reads in the method (exit summary,
     * offers, evaluations) also benefit from the open session.
     */
    @Transactional(readOnly = true)
    public InternDashboardResponse getDashboard(User caller) {
        InternLifecycleStatus status = caller.getLifecycleStatus() != null
                ? caller.getLifecycleStatus()
                : InternLifecycleStatus.REGISTERED;
        String mode = deriveMode(status);
        boolean emailVerified = Boolean.TRUE.equals(caller.getEmailVerified());
        // Phase 6: Step 8 "Evaluation Cycle" completes on first PUBLISHED /
        // ACKNOWLEDGED / AMENDED evaluation for this intern.
        boolean hasPublishedEval = safeHasPublishedEvaluation(caller.getId(), status);

        // Phase 8 — exit summary block only when intern is INACTIVE.
        ExitSummary exitSummary = "INACTIVE".equals(mode) ? loadExitSummary(caller) : null;
        boolean feedbackSubmitted = exitSummary != null && exitSummary.feedbackSubmitted();

        // Selection acknowledgment block — populated only when the latest
        // completed interview decision is SELECTED and ack hasn't fired.
        // Visibility intentionally NOT lifecycle-status-gated: the card
        // must mirror the Send-Offer 409 condition exactly.
        SelectionContext selection = computeSelectionContext(caller);

        // Approach 1 — always-on derived snapshot of "can this intern
        // apply?". The frontend uses it for the completion card +
        // disabled-Apply tooltip; the apply endpoint independently
        // re-derives it for authority.
        ApplyReadiness applyReadiness = profileCompletionService.applyReadiness(caller);

        return new InternDashboardResponse(
                userSummary(caller),
                status,
                mode,
                emailVerified,
                buildStepper(caller, status, hasPublishedEval),
                buildModules(status, mode),
                buildNextAction(caller, status, mode, emailVerified,
                        exitSummary, feedbackSubmitted, selection),
                buildContacts(caller),
                exitSummary,
                selection != null && selection.pendingAck() ? selection.toAck() : null,
                applyReadiness,
                Instant.now()
        );
    }

    /**
     * Pattern-D fix — the terminal "Evaluation Cycle" step depends on the
     * evaluator service reporting whether the intern has any PUBLISHED
     * evaluation. When that lookup transiently fails (network / DB blip),
     * a naive catch-and-return-false regresses the terminal step to
     * UPCOMING for an intern who is already INACTIVE — because
     * {@code done[7] = hasPublishedEval || s == INACTIVE_INTERN} is
     * computed downstream and the caller has no way to distinguish
     * "no eval + inactive" from "lookup failed + inactive".
     *
     * <p>Belt-and-suspenders: an INACTIVE intern is past the evaluation
     * cycle by definition (exited before or after any eval), so a lookup
     * FAILURE for an INACTIVE intern is safe to treat as "cycle done".
     * Any non-INACTIVE status still returns {@code false} on failure —
     * an ACTIVE intern whose lookup blips must not falsely render the
     * terminal step done.</p>
     *
     * <p>Package-private so
     * {@code InternDashboardServiceExceptionSafetyTest} can verify the
     * INACTIVE-vs-ACTIVE branching without spinning the full dashboard.</p>
     */
    boolean safeHasPublishedEvaluation(UUID userId, InternLifecycleStatus status) {
        try {
            return internEvaluationService.internHasPublishedEvaluation(userId);
        } catch (Exception e) {
            log.warn("[Dashboard] evaluation check failed (non-fatal) for {}: {}",
                    userId, e.getMessage());
            return status == InternLifecycleStatus.INACTIVE_INTERN;
        }
    }

    // ── Selection-acknowledgment context ────────────────────────────────────

    /**
     * Lightweight value object describing the most recent application's
     * selection state — used to drive both the dedicated selection-ack card
     * and the INTERVIEW_COMPLETED next-action ("Awaiting decision" vs
     * "Selected — request offer" vs "Offer on its way").
     */
    private record SelectionContext(
            UUID applicationId, String jobTitle, String applicantVisibleNotes,
            boolean isSelected, boolean acknowledged) {
        boolean pendingAck() { return isSelected && !acknowledged; }
        boolean awaitingOffer() { return isSelected && acknowledged; }
        InternDashboardResponse.SelectionAck toAck() {
            return new InternDashboardResponse.SelectionAck(
                    applicationId, jobTitle, applicantVisibleNotes);
        }
    }

    private SelectionContext computeSelectionContext(User caller) {
        // The picker now mirrors the ERM Send-Offer gate at BOTH the
        // predicate level (SelectionAckPolicy.needsAck) AND the
        // application-selection level — i.e. we look directly for an
        // application that needs acknowledgment, instead of picking the
        // latest-scheduled completed interview and then asking whether
        // it happens to be SELECTED. The earlier picker could drift off
        // the gated app in two ways:
        //   1) the standalone Candidate-by-user lookup could return
        //      empty (broken back-link), nulling the entire context
        //      even though the gate, which loads the application by
        //      ID, was firing fine;
        //   2) a candidate with multiple applications could have a
        //      non-SELECTED interview with a later scheduledAt — the
        //      picker would land on that one and report isSelected =
        //      false, hiding the card on the SELECTED app the gate
        //      operates on.
        // Both surfaces now consult selectionAckPolicy.needsAck(app);
        // they cannot disagree.
        try {
            List<Application> apps = applicationRepository
                    .findByCandidateUserIdOrderByAppliedAtDesc(caller.getId());
            int byUserId = apps != null ? apps.size() : 0;
            // Fallback: a stale duplicate User row whose id doesn't match
            // the Candidate.user_id can hide all the caller's apps from
            // the user-id query even though the ERM-side flow (which
            // loads the app by id) sees them fine. The email match
            // bridges the gap for that case.
            int byEmail = 0;
            if ((apps == null || apps.isEmpty())
                    && caller.getEmail() != null && !caller.getEmail().isBlank()) {
                apps = applicationRepository
                        .findByCandidateUserEmailOrderByAppliedAtDesc(caller.getEmail());
                byEmail = apps != null ? apps.size() : 0;
            }
            if (apps == null || apps.isEmpty()) {
                // Visible diagnostic so the deadlock case can be triaged
                // from the server log: tells the operator immediately
                // whether the Candidate.user_id / email link is broken
                // for the caller. INFO so it isn't lost at WARN-only
                // log levels but doesn't spam ERROR.
                log.info("[Dashboard] selection-context: caller={} email={} "
                        + "no Applications found (byUserId={}, byEmail={}); "
                        + "no SelectionAckCard will render. Most likely the "
                        + "caller's User.id + email don't match any "
                        + "Candidate.user_id or Candidate.user.email — verify "
                        + "the test session is logged in as the candidate.",
                        caller.getId(), caller.getEmail(), byUserId, byEmail);
                return null;
            }

            // Pass 1: any application that the gate would 409 on — that
            // application is the one to expose to the intern dashboard.
            // Tiebreak by latest completed-interview scheduledAt so the
            // chosen row is stable when multiple apps qualify.
            Application needsAckApp = null;
            Interview needsAckInterview = null;
            for (Application a : apps) {
                if (a == null || a.getId() == null) continue;
                if (!selectionAckPolicy.needsAck(a)) continue;
                Interview iv = selectionAckPolicy.latestCompletedInterview(a).orElse(null);
                if (needsAckApp == null
                        || (iv != null && iv.getScheduledAt() != null
                            && (needsAckInterview == null
                                || needsAckInterview.getScheduledAt() == null
                                || iv.getScheduledAt().isAfter(needsAckInterview.getScheduledAt())))) {
                    needsAckApp = a;
                    needsAckInterview = iv;
                }
            }
            if (needsAckApp != null) {
                return new SelectionContext(
                        needsAckApp.getId(),
                        needsAckApp.getJobPosting() != null
                                ? needsAckApp.getJobPosting().getTitle() : null,
                        needsAckApp.getApplicantVisibleFeedback() != null
                                ? needsAckApp.getApplicantVisibleFeedback()
                                : (needsAckInterview != null
                                    ? needsAckInterview.getApplicantVisibleNotes() : null),
                        true,   // isSelected — guaranteed by needsAck
                        false); // acknowledged — guaranteed false by needsAck
            }

            // Pass 2: nobody needs ack right now; an awaiting-offer app
            // (selected + already acknowledged) drives the
            // INTERVIEW_COMPLETED "Offer on its way" next-action text.
            Application awaitingApp = null;
            Interview awaitingInterview = null;
            for (Application a : apps) {
                if (a == null || a.getId() == null) continue;
                if (!selectionAckPolicy.isSelected(a)) continue;
                if (a.getSelectionAcknowledgedAt() == null) continue;
                Interview iv = selectionAckPolicy.latestCompletedInterview(a).orElse(null);
                if (awaitingApp == null
                        || (iv != null && iv.getScheduledAt() != null
                            && (awaitingInterview == null
                                || awaitingInterview.getScheduledAt() == null
                                || iv.getScheduledAt().isAfter(awaitingInterview.getScheduledAt())))) {
                    awaitingApp = a;
                    awaitingInterview = iv;
                }
            }
            if (awaitingApp != null) {
                return new SelectionContext(
                        awaitingApp.getId(),
                        awaitingApp.getJobPosting() != null
                                ? awaitingApp.getJobPosting().getTitle() : null,
                        awaitingApp.getApplicantVisibleFeedback() != null
                                ? awaitingApp.getApplicantVisibleFeedback()
                                : (awaitingInterview != null
                                    ? awaitingInterview.getApplicantVisibleNotes() : null),
                        true,   // isSelected
                        true);  // acknowledged → awaitingOffer
            }
            return null;
        } catch (Exception e) {
            log.warn("[Dashboard] selection-context lookup failed (non-fatal) for {}: {}",
                    caller.getId(), e.getMessage());
            return null;
        }
    }

    private ExitSummary loadExitSummary(User caller) {
        try {
            return exitService.getInternSummary(caller)
                    .map(s -> new ExitSummary(
                            s.exitType(),
                            s.exitDate(),
                            s.durationDays(),
                            s.projectsCompleted(),
                            s.evaluationsCount(),
                            s.averageScore(),
                            s.timesheetsApproved(),
                            s.totalApprovedHours(),
                            s.feedbackSubmitted(),
                            s.internVisibleSummary(),
                            s.finalEvaluationId()))
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[Dashboard] exit summary lookup failed (non-fatal) for {}: {}",
                    caller.getId(), e.getMessage());
            return null;
        }
    }

    // ── Mode derivation ─────────────────────────────────────────────────────

    // Package-private + static so the buildModules matrix test can drive
    // it directly without wiring the full @Service.
    static String deriveMode(InternLifecycleStatus s) {
        return switch (s) {
            case REGISTERED, EMAIL_VERIFIED, APPLICATION_SUBMITTED -> "APPLICANT";
            case SHORTLISTED, INTERVIEW_SCHEDULED, INTERVIEW_COMPLETED -> "INTERVIEW";
            case OFFER_SENT -> "OFFER";
            case OFFER_SIGNED, EMPLOYEE_ID_CREATED, ONBOARDING_ASSIGNED -> "NEW_HIRE";
            case ONBOARDING_ACCEPTED, ACTIVE_INTERN -> "ACTIVE_INTERN";
            case INACTIVE_INTERN -> "INACTIVE";
        };
    }

    // ── Stepper ─────────────────────────────────────────────────────────────

    private static final String[][] STEPS = {
            {"registered",       "Registered"},
            {"email_verified",   "Email Verified"},
            {"applied",          "Applied"},
            {"interview",        "Interview"},
            {"offer",            "Offer"},
            {"onboarding",       "Onboarding"},
            {"active_intern",    "Active Intern"},
            {"evaluation_cycle", "Evaluation Cycle"},
            {"completed",        "Completed / Inactive"},
    };

    private List<StepperStep> buildStepper(User caller, InternLifecycleStatus s,
                                            boolean hasPublishedEval) {
        // For each step compute the done-predicate. Active = first not-done.
        boolean[] done = new boolean[STEPS.length];
        done[0] = true; // Step 1 — user exists by virtue of being logged in
        done[1] = atLeast(s, InternLifecycleStatus.EMAIL_VERIFIED);
        done[2] = atLeast(s, InternLifecycleStatus.APPLICATION_SUBMITTED);
        done[3] = atLeast(s, InternLifecycleStatus.INTERVIEW_COMPLETED);
        done[4] = atLeast(s, InternLifecycleStatus.OFFER_SIGNED);
        done[5] = atLeast(s, InternLifecycleStatus.ONBOARDING_ACCEPTED);
        done[6] = atLeast(s, InternLifecycleStatus.ACTIVE_INTERN);
        // Phase 6: Step 8 completes on first PUBLISHED evaluation. Also marked
        // DONE if the intern is INACTIVE (cycle implicitly finished).
        done[7] = hasPublishedEval || s == InternLifecycleStatus.INACTIVE_INTERN;
        done[8] = s == InternLifecycleStatus.INACTIVE_INTERN;

        int firstActive = -1;
        for (int i = 0; i < done.length; i++) {
            if (!done[i]) { firstActive = i; break; }
        }

        // Phase 8.9.1 — the start-date gate is gone, so an intern at
        // ONBOARDING_ACCEPTED with a signed offer flips immediately via the
        // doc-completion trigger. The only remaining "parked at Active
        // Intern" case is the defensive one where onboarding was accepted
        // but no SIGNED offer exists — surfaced as "Pending offer signing".
        String activeIntenReason = null;
        if (firstActive == 6 && s == InternLifecycleStatus.ONBOARDING_ACCEPTED) {
            activeIntenReason = computeActivationReason(caller);
        }

        List<StepperStep> out = new ArrayList<>(STEPS.length);
        for (int i = 0; i < STEPS.length; i++) {
            String state = done[i] ? "DONE"
                    : (i == firstActive ? "ACTIVE" : "UPCOMING");
            String reason = (i == 6) ? activeIntenReason : null;
            out.add(new StepperStep(STEPS[i][0], STEPS[i][1], state, reason));
        }
        return out;
    }

    /**
     * Phase 8.9.1 — subtitle for a ONBOARDING_ACCEPTED intern still parked
     * at the Active Intern node. The start-date gate is gone, so the only
     * remaining reason an intern lingers here is a missing SIGNED offer
     * (a defensive edge case — onboarding shouldn't normally have been
     * accepted without one). Returns null when the offer is signed, since
     * the doc-completion trigger should be activating the intern within
     * seconds; a transient mid-window subtitle would just churn the UI.
     */
    private String computeActivationReason(User caller) {
        try {
            List<Offer> offers = offerRepository
                    .findByApplication_Candidate_User_IdOrderByCreatedAtDesc(caller.getId());
            boolean hasSignedOffer = offers.stream().anyMatch(o ->
                    o.getStatus() == OfferStatus.SIGNED
                            || o.getStatus() == OfferStatus.ACCEPTED);
            if (!hasSignedOffer) return "Pending offer signing";
            // Signed + onboarding accepted ⇒ activation fires immediately
            // via the doc-completion trigger. No subtitle needed.
            return null;
        } catch (Exception e) {
            log.warn("[Dashboard] activation reason lookup failed (non-fatal) for {}: {}",
                    caller.getId(), e.getMessage());
            return null;
        }
    }

    // Package-private + static so the buildModules test can call it too.
    static boolean atLeast(InternLifecycleStatus current, InternLifecycleStatus floor) {
        return current.ordinal() >= floor.ordinal();
    }

    // ── Modules ─────────────────────────────────────────────────────────────

    /**
     * Wave-2 #4 — progressive reveal. Every module that isn't yet relevant
     * for the caller's lifecycle stage is HIDDEN, not "greyed-out locked".
     * A fresh intern at EMAIL_VERIFIED therefore sees only Home / Job
     * Postings / Messages / Help (+ Profile, which has no moduleKey and is
     * always visible in the sidebar). Modules appear as the intern reaches
     * the stage that needs them:
     *
     * <ul>
     *   <li>{@code myApplications} — from APPLICATION_SUBMITTED (they have
     *       something to view); read-only once past OFFER_SENT.</li>
     *   <li>{@code interviewCenter} — from SHORTLISTED; read-only past
     *       OFFER_SENT.</li>
     *   <li>{@code offerLetter} — from OFFER_SENT; read-only past
     *       OFFER_SIGNED (they can still view the executed copy).</li>
     *   <li>{@code onboarding} — from OFFER_SIGNED / EMPLOYEE_ID_CREATED /
     *       ONBOARDING_ASSIGNED (NEW_HIRE mode).</li>
     *   <li>{@code documents} — from OFFER_SIGNED onward (nothing to store
     *       before then); read-only when INACTIVE.</li>
     *   <li>{@code myProjects / timesheets / evaluations / doubts} — only
     *       ACTIVE_INTERN; INACTIVE keeps them visible read-only.</li>
     *   <li>{@code jobPostings} — always visible in the application phase
     *       so an intern with a slow-moving first application can still
     *       apply to more; hidden once ACTIVE/INACTIVE.</li>
     *   <li>{@code messages / help / home} — always visible.</li>
     * </ul>
     *
     * <p>Belt-and-suspenders: the frontend {@code InternModuleRouteGuard}
     * redirects any hidden-or-locked route to /careers/intern, so a stale
     * bookmark can't bypass the reveal.</p>
     */
    // Package-private + static — pure function of status + mode with no
    // dependence on injected beans. Exposed to the test for the module
    // matrix drill.
    static Modules buildModules(InternLifecycleStatus status, String mode) {
        boolean inactive = "INACTIVE".equals(mode);
        // "Post-application-phase" — the intern has moved past the pre-hire
        // pipeline into work/exit. Used to hide the application-family
        // modules (jobs / apps / interview / offer / onboarding).
        boolean workOrLater = inactive || "ACTIVE_INTERN".equals(mode);

        ModuleState home = new ModuleState(true, false, inactive);

        // Job Postings — visible for the whole application phase; hidden
        // once the intern has been hired (there is no jobs page for
        // ACTIVE / INACTIVE interns to hit).
        ModuleState jobs = workOrLater
                ? hidden()
                : new ModuleState(true, false, false);

        // My Applications — appears when the intern has actually applied
        // to at least one job. Read-only once they've been sent an offer
        // (the record freezes; further actions happen on Offer Letter).
        ModuleState apps;
        if (workOrLater) {
            apps = hidden();
        } else if (atLeast(status, InternLifecycleStatus.APPLICATION_SUBMITTED)) {
            boolean appsReadOnly = !(mode.equals("APPLICANT") || mode.equals("INTERVIEW"));
            apps = new ModuleState(true, false, appsReadOnly);
        } else {
            apps = hidden();
        }

        // Interview Center — appears when the intern has been SHORTLISTED
        // (they have an interview to prepare for). Read-only past OFFER_SENT.
        ModuleState interview;
        if (workOrLater) {
            interview = hidden();
        } else if (atLeast(status, InternLifecycleStatus.SHORTLISTED)) {
            boolean ro = !"INTERVIEW".equals(mode);
            interview = new ModuleState(true, false, ro);
        } else {
            interview = hidden();
        }

        // Offer Letter — appears when the offer has been sent. Read-only
        // past OFFER_SIGNED so the intern can still view the executed copy.
        ModuleState offer;
        if (workOrLater) {
            offer = hidden();
        } else if (atLeast(status, InternLifecycleStatus.OFFER_SENT)) {
            boolean ro = !"OFFER".equals(mode);
            offer = new ModuleState(true, false, ro);
        } else {
            offer = hidden();
        }

        // Onboarding — appears from OFFER_SIGNED (NEW_HIRE mode); the
        // sidebar doesn't render an onboarding link today (the flow uses
        // Documents), but this stays as the canonical gate for any future
        // sidebar entry and for the route guard.
        ModuleState onboarding = "NEW_HIRE".equals(mode)
                ? new ModuleState(true, false, false)
                : hidden();

        // My Projects / Timesheets / Evaluations / Doubts — only appear once
        // the intern is ACTIVE (INACTIVE keeps historical read-only access).
        ModuleState projects = workModuleState(mode);
        ModuleState timesheets = workModuleState(mode);
        ModuleState evaluations = workModuleState(mode);
        ModuleState doubts = workModuleState(mode);

        // Documents — appears from OFFER_SIGNED onward. Pre-offer interns
        // have no documents in the system yet, so hiding it keeps the
        // starter sidebar to the five items the Wave-2 spec calls for.
        ModuleState documents;
        if (atLeast(status, InternLifecycleStatus.OFFER_SIGNED)) {
            documents = new ModuleState(true, false, inactive);
        } else {
            documents = hidden();
        }

        // Messages — visible for everyone; locked when INACTIVE (they can
        // still read history but can't compose new threads).
        ModuleState messages = inactive
                ? new ModuleState(true, true, false)
                : new ModuleState(true, false, false);

        // Help — always visible
        ModuleState help = new ModuleState(true, false, false);

        return new Modules(home, jobs, apps, interview, offer, onboarding,
                projects, timesheets, evaluations, documents, messages, doubts, help);
    }

    private static ModuleState hidden() {
        return new ModuleState(false, false, false);
    }

    private static ModuleState workModuleState(String mode) {
        // My Projects / Timesheets / Evaluations / Doubts are gated on
        // ACTIVE_INTERN. Pre-active interns are hidden entirely (visible
        // = false) — greyed-out links confused new hires into thinking
        // timesheets were already required. INACTIVE keeps visibility for
        // historical read-only access.
        return switch (mode) {
            case "ACTIVE_INTERN" -> new ModuleState(true, false, false);
            case "INACTIVE"      -> new ModuleState(true, false, true);
            default              -> hidden();
        };
    }

    // ── Next-action card ────────────────────────────────────────────────────

    private NextAction buildNextAction(User caller,
                                       InternLifecycleStatus s, String mode,
                                       boolean emailVerified,
                                       ExitSummary exitSummary,
                                       boolean feedbackSubmitted,
                                       SelectionContext selection) {
        // Pipeline-restore — highest-priority next-action for interns
        // with a live IDMS offer-family instance. Runs BEFORE the
        // lifecycle-status switch so a SENT_TO_INTERN or RETURNED offer
        // card is shown even if the lifecycle status has drifted (e.g.
        // OFFER_SENT recorded once by legacy path but IDMS offer just
        // returned for corrections). Never surfaces for INACTIVE interns.
        if (s != InternLifecycleStatus.INACTIVE_INTERN && caller != null) {
            NextAction offerCard = offerLetterNextAction(caller);
            if (offerCard != null) return offerCard;
        }
        if (s == InternLifecycleStatus.INACTIVE_INTERN) {
            if (!feedbackSubmitted) {
                return action(
                        "Share your exit feedback",
                        "Help us improve future internships — takes about 3 minutes.",
                        "Open feedback form",
                        "/careers/intern/exit/feedback",
                        false, null);
            }
            String description = exitSummary != null && exitSummary.internVisibleSummary() != null
                    ? exitSummary.internVisibleSummary()
                    : "Your record is read-only. Download signed forms and feedback any time.";
            return action(
                    "Internship concluded",
                    description,
                    "View exit summary",
                    "/careers/intern/exit/summary",
                    false, null);
        }
        return switch (s) {
            case REGISTERED -> action(
                    "Verify your email",
                    "Check your inbox for the verification link.",
                    "Resend verification",
                    "/api/v1/auth/resend-verification",
                    false, null);
            case EMAIL_VERIFIED -> action(
                    "Apply to a job",   
                    "Browse open positions and submit your application.",
                    "Browse jobs", "/careers/intern/jobs",
                    false, null);
            case APPLICATION_SUBMITTED -> waiting(
                    "Application under review",
                    "ERM is reviewing your application. You'll hear back soon.",
                    "ERM");
            case SHORTLISTED -> waiting(
                    "Interview will be scheduled",
                    "You've been shortlisted. ERM will send your interview details shortly.",
                    "ERM");
            case INTERVIEW_SCHEDULED -> action(
                    "Interview scheduled",
                    "Join the Zoom call at the scheduled time.",
                    "View interview", "/careers/intern/interviews",
                    false, null);
            case INTERVIEW_COMPLETED -> {
                if (selection != null && selection.pendingAck()) {
                    // Same shared SelectionAckPolicy as the ERM
                    // Send-Offer gate — when needsAck is true the
                    // candidate must act, so the NEXT ACTION is the
                    // ack prompt itself with the button. The CTA href
                    // points at the existing POST endpoint; the
                    // frontend NextActionCard renders an inline
                    // button (mirroring the resend-verification
                    // pattern). The action() helper produces no
                    // "Waiting on ERM" badge, fixing the prior
                    // mislabel.
                    yield action(
                            "You've been selected — receive your offer letter",
                            "Great news — your interview was a success. Click below to "
                                    + "acknowledge and we'll prepare and send your offer letter "
                                    + "right after.",
                            "Receive my offer letter",
                            // Full /api/v1 path — the frontend axios client uses
                            // baseURL=http://localhost:8080 with no prefix
                            // rewrite, so bare /applications/... 404s. Matches
                            // ApplicationController @RequestMapping at
                            // /api/v1/applications.
                            "/api/v1/applications/" + selection.applicationId() + "/acknowledge-selection",
                            false, null);
                }
                if (selection != null && selection.awaitingOffer()) {
                    // W3 #15 — wording tightened per user request. The
                    // prior message ("Offer letter on its way / Thanks
                    // for acknowledging…") was fine but the leading
                    // line implied the letter was already in motion;
                    // ERM may still be preparing. This variant leads
                    // with the ack receipt (the visible thing the
                    // intern just did) then previews what's next.
                    yield waiting(
                            "We've received your acknowledgement",
                            "Your offer letter will be sent shortly. "
                                    + "You'll get an email the moment it's ready to sign.",
                            "ERM");
                }
                yield waiting(
                        "Awaiting interview feedback",
                        "The interviewer is recording feedback.",
                        "ERM");
            }
            case OFFER_SENT -> action(
                    "Sign your offer letter",
                    "Review and sign the offer through IDMS.",
                    "Open offer letter", "/careers/intern/offer-letter",
                    false, null);
            case OFFER_SIGNED -> waiting(
                    "Welcome! Setting up your account",
                    "Your employee ID is being created.",
                    "system");
            case EMPLOYEE_ID_CREATED -> waiting(
                    // W3 #15 — softened the alarmed "Onboarding packet
                    // pending" copy that made the intern think something
                    // was overdue. At this stage the offer is signed and
                    // ERM is preparing the onboarding documents; the
                    // intern doesn't need to do anything.
                    "Your onboarding packet is being prepared",
                    "Your offer is signed and your employee record is set up. "
                            + "ERM is putting together your onboarding documents — "
                            + "you'll get an email when they're ready to complete.",
                    "ERM");
            case ONBOARDING_ASSIGNED -> action(
                    "Complete onboarding documents",
                    "W-4, I-9, ACH, emergency contact, handbook acknowledgment.",
                    "Open onboarding", "/careers/intern/documents",
                    false, null);
            case ONBOARDING_ACCEPTED -> waiting(
                    "Awaiting start date activation",
                    "Onboarding accepted. Your start date will activate your account.",
                    "system");
            case ACTIVE_INTERN -> action(
                    "View your current work",
                    "Check projects, weekly meetings, timesheets, and evaluations.",
                    "Open work", "/careers/intern/projects",
                    false, null);
            // INACTIVE_INTERN handled above (Phase 8 branch) — kept here to
            // satisfy exhaustive switch even though never reached.
            case INACTIVE_INTERN -> action(
                    "Internship concluded",
                    "Your record is read-only.",
                    "View exit summary", "/careers/intern/exit/summary",
                    false, null);
        };
    }

    private NextAction action(String title, String desc, String ctaLabel,
                              String ctaHref, boolean waiting, String waitingFor) {
        return new NextAction(title, desc, ctaLabel, ctaHref, waiting, waitingFor);
    }

    private NextAction waiting(String title, String desc, String waitingFor) {
        return new NextAction(title, desc, null, null, true, waitingFor);
    }

    /**
     * Pipeline-restore — resolve the offer-family next-action card, or
     * {@code null} if the intern has no live offer to act on.
     *
     * <p>Card decisions by IDMS status of the intern's newest
     * offer-family instance (matched case-insensitively by
     * {@link com.anvicorp.api.erm.idms.DocumentInstanceRepository#findOfferFamilyForIntern}
     * — {@code LOWER(templateKey) LIKE '%offer\_%'}):</p>
     * <ul>
     *   <li>{@code SENT_TO_INTERN} — "Your offer letter is ready — review
     *       and sign" (deep-link to the fill/sign page).</li>
     *   <li>{@code RETURNED} — "Your offer letter came back with
     *       corrections" (same link).</li>
     *   <li>{@code INTERN_SUBMITTED} or {@code VERIFIED} — waiting on ERM.</li>
     *   <li>{@code FINALIZED} / {@code REVOKED} / {@code SUPERSEDED} —
     *       yield to the lifecycle-status branches (no card).</li>
     * </ul>
     */
    private NextAction offerLetterNextAction(User caller) {
        var latest = documentInstanceRepository
                .findOfferFamilyForIntern(caller.getId())
                .stream().findFirst().orElse(null);
        if (latest == null) return null;
        String url = "/careers/intern/offer-letter/" + latest.getId();
        return switch (latest.getStatus()) {
            case SENT_TO_INTERN -> action(
                    "Your offer letter is ready — review and sign",
                    "Open your offer, complete any intern fields, and sign.",
                    "Open offer letter", url, false, null);
            case RETURNED -> action(
                    "Your offer letter came back with corrections",
                    "Address the changes your ERM requested, then re-submit.",
                    "Open offer letter", url, false, null);
            case INTERN_SUBMITTED, VERIFIED -> waiting(
                    "Your offer is with ERM for review",
                    "You'll see the executed copy the moment it's finalized.",
                    "ERM");
            case DRAFT, FINALIZED, REVOKED, SUPERSEDED, VOIDED -> null;
        };
    }

    // ── Contacts ────────────────────────────────────────────────────────────

    private Contacts buildContacts(User caller) {
        // Trainer + Evaluator route through OrgTeamResolver so the
        // single org-wide trainer / evaluator (from DEFAULT_TRAINER_EMAIL
        // / DEFAULT_EVALUATOR_EMAIL) populates the slot even when the
        // per-intern FK was never stamped — fixes the "Your team shows
        // only ERM + Manager" gap on activations that ran before the
        // env vars resolved. Manager + ERM stay direct (per-intern).
        try {
            InternLifecycle lc = internLifecycleRepository.findByUserId(caller.getId())
                    .orElse(null);
            if (lc == null) return new Contacts(null, null, null, null);
            return new Contacts(
                    lookupContact(lc.getErmId()),
                    lookupContact(orgTeamResolver.resolveTrainerId(lc)),
                    lookupContact(orgTeamResolver.resolveEvaluatorId(lc)),
                    lookupContact(lc.getManagerId()));
        } catch (Exception e) {
            log.warn("Contacts lookup failed (non-fatal) for {}: {}",
                    caller.getId(), e.getMessage());
            return new Contacts(null, null, null, null);
        }
    }

    private Contact lookupContact(UUID userId) {
        if (userId == null) return null;
        return userRepository.findById(userId)
                .map(u -> new Contact(
                        u.getFullName() != null ? u.getFullName() : u.getEmail(),
                        u.getEmail()))
                .orElse(null);
    }

    // ── User summary ────────────────────────────────────────────────────────

    private UserSummary userSummary(User u) {
        String first = "";
        String last = "";
        String full = u.getFullName();
        if (full != null && !full.isBlank()) {
            String[] parts = full.trim().split("\\s+", 2);
            first = parts[0];
            last = parts.length > 1 ? parts[1] : "";
        }
        return new UserSummary(
                first,
                last,
                u.getEmail(),
                u.getApplicantId(),
                u.getEmployeeId()
        );
    }
}
