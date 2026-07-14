package com.anvicorp.api.erm.newhire;

import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.InternLifecycleStatus;
import com.anvicorp.api.enums.MailHandoverState;
import com.anvicorp.api.erm.newhire.OnboardingTrackerDtos.ActionType;
import com.anvicorp.api.erm.newhire.OnboardingTrackerDtos.OnboardingStep;
import com.anvicorp.api.erm.newhire.OnboardingTrackerDtos.OnboardingTracker;
import com.anvicorp.api.erm.newhire.OnboardingTrackerDtos.StepActor;
import com.anvicorp.api.erm.newhire.OnboardingTrackerDtos.StepId;
import com.anvicorp.api.erm.newhire.OnboardingTrackerDtos.StepStatus;
import com.anvicorp.api.erm.newhire.OnboardingTrackerDtos.SubTask;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.intern.OrgTeamResolver;
import com.anvicorp.api.notification.EmailProvider;
import com.anvicorp.api.notification.UserNotificationDispatcher;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.OfferRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Computes the 6-step onboarding tracker payload for a single intern
 * lifecycle. Pure projection over existing state — the only new column
 * is {@code intern_lifecycles.team_notified_at} (step 4); steps 1, 2,
 * 3, 5a, 5b, 6 are derived from data the system already tracks.
 *
 * <p>Also hosts the two new action handlers used by the tracker
 * ({@link #notifyTeam}, {@link #sendSignatureReminder}) — kept here
 * rather than in {@link ErmNewHireService} so the gated-tracker code
 * lives in one place.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingTrackerService {

    private final InternLifecycleRepository lifecycleRepository;
    private final UserRepository userRepository;
    private final OfferRepository offerRepository;
    private final OrgTeamResolver orgTeamResolver;
    private final UserNotificationDispatcher userNotifications;
    private final EmailProvider emailProvider;
    private final JdbcTemplate jdbc;
    private final com.anvicorp.api.config.BrandConfig brand;

    // ── Read ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public OnboardingTracker compute(UUID lifecycleId) {
        InternLifecycle lc = lifecycleRepository.findById(lifecycleId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + lifecycleId));
        return computeForLifecycle(lc);
    }

    /**
     * Lighter overload used by {@link ErmNewHireService#list} so the row
     * DTO can carry stepsCompleted + nextStepLabel without an N+1 round
     * trip per intern. Tries to skip the optional sub-lookups when state
     * already proves a step is DONE (e.g. lifecycle = ACTIVE_INTERN ⇒ all
     * six steps DONE without needing to inspect the offer).
     */
    @Transactional(readOnly = true)
    public OnboardingTracker computeForLifecycle(InternLifecycle lc) {
        User intern = lc.getUserId() != null
                ? userRepository.findById(lc.getUserId()).orElse(null)
                : null;
        Offer latestOffer = null;
        if (intern != null) {
            latestOffer = offerRepository
                    .findByApplication_Candidate_User_IdOrderByCreatedAtDesc(intern.getId())
                    .stream()
                    .findFirst().orElse(null);
        }
        return build(lc, intern, latestOffer);
    }

    // ── Step 4 action: notify trainer + manager ──────────────────────────────

    /**
     * Fan-out for "new intern joined" — sends an in-app notification and
     * a short email to the resolved single trainer + single manager, then
     * stamps {@code intern_lifecycles.team_notified_at} so the step
     * flips to DONE. Idempotent: re-notifies if called twice; the
     * timestamp is overwritten with the latest fire moment.
     */
    @Transactional
    public OnboardingTracker notifyTeam(UUID lifecycleId, User caller) {
        InternLifecycle lc = lifecycleRepository.findById(lifecycleId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + lifecycleId));
        User intern = lc.getUserId() != null
                ? userRepository.findById(lc.getUserId()).orElse(null)
                : null;
        String internName = intern != null && intern.getFullName() != null
                ? intern.getFullName() : "a new intern";
        Instant when = Instant.now();

        Optional<User> trainer = orgTeamResolver.resolveTrainer(lc);
        Optional<User> evaluator = orgTeamResolver.resolveEvaluator(lc);
        User manager = lc.getManagerId() != null
                ? userRepository.findById(lc.getManagerId()).orElse(null)
                : null;

        notifyOne("trainer", trainer.orElse(null), intern, internName);
        notifyOne("evaluator", evaluator.orElse(null), intern, internName);
        notifyOne("manager", manager, intern, internName);

        lc.setTeamNotifiedAt(when);
        lifecycleRepository.save(lc);

        log.info("[OnboardingTracker] team-notified lifecycle={} intern={} actor={}",
                lc.getId(), intern != null ? intern.getId() : null,
                caller != null ? caller.getId() : null);
        return computeForLifecycle(lc);
    }

    private void notifyOne(String role, User recipient, User intern, String internName) {
        if (recipient == null || recipient.getEmail() == null
                || recipient.getEmail().isBlank()) {
            log.info("[OnboardingTracker] notify {} skipped — recipient unresolved", role);
            return;
        }
        String title = "New intern joined — " + internName;
        String body = internName + " has accepted their offer and onboarding "
                + "is in progress. You'll see them in your dashboard.";
        String url = "/careers/" + (role.equals("manager") ? "manager"
                : role.equals("evaluator") ? "evaluator" : "trainer") + "/active-interns";
        try {
            userNotifications.dispatch(recipient.getId(), "INTERN_ONBOARDING_ANNOUNCED",
                    intern != null ? intern.getId() : null,
                    title, body, url, true);
        } catch (Exception e) {
            log.warn("[OnboardingTracker] in-app notify {} failed: {}", role, e.getMessage());
        }
        try {
            String plain = "Hi " + firstName(recipient) + ",\n\n"
                    + internName + " has accepted their offer with " + brand.getName() + " and "
                    + "their onboarding is in progress. They'll appear in your "
                    + "dashboard once activated.\n\nView: " + url + "\n\n" + brand.signoffErm();
            emailProvider.sendBrandedHtml(recipient.getEmail(), title, plain,
                    "<p>" + escape(plain).replace("\n", "<br>") + "</p>");
        } catch (Exception e) {
            log.warn("[OnboardingTracker] email notify {} failed: {}", role, e.getMessage());
        }
    }

    // ── Step 2 action: send signature reminder ───────────────────────────────

    /**
     * Re-notifies the intern that their offer is awaiting signature. Useful
     * when the offer was sent days ago and the intern hasn't yet logged in.
     * Reuses the {@link EmailProvider} surface; the offer-side reminder
     * counter is incremented by the caller if {@link OfferIdmsSigningService}'s
     * dedicated reminder endpoint exists — this is the lightweight
     * always-available nudge.
     */
    @Transactional
    public void sendSignatureReminder(UUID lifecycleId, User caller) {
        InternLifecycle lc = lifecycleRepository.findById(lifecycleId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + lifecycleId));
        User intern = lc.getUserId() != null
                ? userRepository.findById(lc.getUserId()).orElse(null)
                : null;
        if (intern == null || intern.getEmail() == null) {
            log.warn("[OnboardingTracker] signature reminder: intern email missing for lc={}",
                    lc.getId());
            return;
        }
        String url = "/careers/intern/offers";
        String title = "Reminder: please sign your " + brand.getName() + " offer";
        String plain = "Hi " + firstName(intern) + ",\n\nYour offer letter is waiting "
                + "for your e-signature. Sign in to view + sign: " + url
                + "\n\nThis is a friendly reminder from your ERM ("
                + (caller != null && caller.getFullName() != null ? caller.getFullName() : brand.getName())
                + ").\n\n" + brand.signoff();
        try {
            emailProvider.sendBrandedHtml(intern.getEmail(), title, plain,
                    "<p>" + escape(plain).replace("\n", "<br>") + "</p>");
        } catch (Exception e) {
            log.warn("[OnboardingTracker] signature reminder email failed: {}", e.getMessage());
        }
        try {
            userNotifications.dispatch(intern.getId(), "OFFER_SIGN_REMINDER",
                    intern.getId(), title,
                    "Your offer letter is waiting for your signature.", url, true);
        } catch (Exception e) {
            log.warn("[OnboardingTracker] signature reminder in-app failed: {}", e.getMessage());
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private OnboardingTracker build(InternLifecycle lc, User intern, Offer latestOffer) {
        // Offer state is no longer surfaced as tracker steps (the offer is
        // already signed when an InternLifecycle row exists; the two prior
        // OFFER_SENT/OFFER_SIGNED steps were always DONE on this surface
        // and just added noise). The Offer arg is kept on the build()
        // signature so legacy callers don't break — unused here.
        boolean docsAccepted = intern != null
                && intern.getLifecycleStatus() != null
                && (intern.getLifecycleStatus() == InternLifecycleStatus.ONBOARDING_ACCEPTED
                    || intern.getLifecycleStatus() == InternLifecycleStatus.ACTIVE_INTERN
                    || intern.getLifecycleStatus() == InternLifecycleStatus.INACTIVE_INTERN);
        String packetStatus = loadPacketStatus(lc.getId());
        boolean docsAssigned = packetStatus != null; // any packet row = assigned
        boolean teamNotified = lc.getTeamNotifiedAt() != null;
        boolean mailActivated = intern != null
                && intern.getMailHandoverState() == MailHandoverState.ACTIVATED;
        boolean joiningSet = lc.getJoiningDate() != null;
        boolean mailAndJoiningDone = mailActivated && joiningSet;
        boolean active = "ACTIVE".equals(lc.getActiveStatus());
        // Silence "unused" on the kept-for-back-compat offer param.
        @SuppressWarnings("unused")
        Offer _unusedOffer = latestOffer;

        List<OnboardingStep> steps = new ArrayList<>(5);
        steps.add(buildDocsAssigned(lc, docsAssigned, docsAccepted));
        steps.add(buildDocsVerified(lc, docsAccepted, packetStatus, docsAssigned));
        steps.add(buildTeamNotified(lc, teamNotified, docsAccepted));
        steps.add(buildMailAndJoining(lc, mailActivated, joiningSet,
                mailAndJoiningDone, teamNotified, docsAccepted));
        steps.add(buildActivate(lc, active,
                docsAssigned, docsAccepted, teamNotified, mailAndJoiningDone));

        // First non-DONE step becomes CURRENT (unless it's already WAITING_INTERN
        // or LOCKED — those statuses are sticky). The tracker exposes a single
        // currentStepId so the Next banner always knows which step to anchor.
        StepId currentStepId = null;
        for (OnboardingStep s : steps) {
            if (s.status() != StepStatus.DONE) {
                currentStepId = s.id();
                break;
            }
        }
        int stepsCompleted = (int) steps.stream()
                .filter(s -> s.status() == StepStatus.DONE)
                .count();
        final StepId currentStepIdFinal = currentStepId;
        OnboardingStep current = currentStepIdFinal == null ? null
                : steps.stream().filter(s -> s.id() == currentStepIdFinal).findFirst().orElse(null);
        String nextStepLabel = current == null ? null
                : (current.status() == StepStatus.WAITING_INTERN ? "waiting on intern — "
                    : "needs ") + current.label().toLowerCase();
        boolean canActivate = docsAssigned && docsAccepted
                && teamNotified && mailAndJoiningDone;

        return new OnboardingTracker(
                lc.getId(), steps, currentStepId,
                stepsCompleted, 5, 5 - stepsCompleted,
                nextStepLabel, canActivate);
    }

    private OnboardingStep buildDocsAssigned(InternLifecycle lc, boolean assigned,
                                              boolean docsAccepted) {
        // Once docs are accepted (or any later state), the assign step is
        // implicitly DONE too — defensive in case a packet row was deleted
        // without the lifecycle being rolled back.
        if (assigned || docsAccepted) {
            return new OnboardingStep(StepId.DOCS_ASSIGNED, "Assign documents",
                    StepStatus.DONE, StepActor.ERM, ActionType.NONE,
                    null, null, null, List.of());
        }
        // No packet on file — ERM opens the AssignPacketModal on the
        // detail page. The redirect href returns the user to the same
        // detail page (where the tracker is mounted); the frontend
        // tracker also exposes an explicit "open packet modal" callback
        // wired by the detail page so a click can launch the modal
        // without a navigation round-trip.
        return new OnboardingStep(StepId.DOCS_ASSIGNED, "Assign documents",
                StepStatus.CURRENT, StepActor.ERM, ActionType.MODAL,
                null,
                "Assign the intern's document packet so they can start uploading.",
                "/careers/erm/new-hire/" + lc.getId(), List.of());
    }

    private OnboardingStep buildDocsVerified(InternLifecycle lc, boolean done,
                                              String packetStatus, boolean docsAssigned) {
        if (done) {
            return new OnboardingStep(StepId.DOCS_VERIFIED, "Verify documents",
                    StepStatus.DONE, StepActor.ERM, ActionType.NONE,
                    null, null, null, List.of());
        }
        if (!docsAssigned) {
            return new OnboardingStep(StepId.DOCS_VERIFIED, "Verify documents",
                    StepStatus.PENDING, StepActor.ERM, ActionType.NONE,
                    null, "Available once the document packet is assigned.",
                    null, List.of());
        }
        // Packet assigned but intern is still filling.
        boolean waitingOnIntern = "ASSIGNED".equalsIgnoreCase(packetStatus)
                || "IN_PROGRESS".equalsIgnoreCase(packetStatus);
        if (waitingOnIntern) {
            return new OnboardingStep(StepId.DOCS_VERIFIED, "Verify documents",
                    StepStatus.WAITING_INTERN, StepActor.INTERN, ActionType.WAIT_REMINDER,
                    null,
                    "Waiting for the intern to complete + submit their document packet. "
                            + "Open the review screen to nudge them or follow up.",
                    "/careers/erm/document-review/" + lc.getId(), List.of());
        }
        // ALL_SUBMITTED or similar — needs ERM review/acceptance.
        return new OnboardingStep(StepId.DOCS_VERIFIED, "Verify documents",
                StepStatus.CURRENT, StepActor.ERM, ActionType.REDIRECT,
                null,
                "Intern submitted documents — review and accept each one to move forward.",
                "/careers/erm/document-review/" + lc.getId(), List.of());
    }

    private OnboardingStep buildTeamNotified(InternLifecycle lc, boolean done,
                                              boolean docsAccepted) {
        if (done) {
            return new OnboardingStep(StepId.TEAM_NOTIFIED, "Notify trainer + manager",
                    StepStatus.DONE, StepActor.ERM, ActionType.NONE,
                    lc.getTeamNotifiedAt(), null, null, List.of());
        }
        if (!docsAccepted) {
            return new OnboardingStep(StepId.TEAM_NOTIFIED, "Notify trainer + manager",
                    StepStatus.PENDING, StepActor.ERM, ActionType.NONE,
                    null, "Available once documents are verified.", null, List.of());
        }
        return new OnboardingStep(StepId.TEAM_NOTIFIED, "Notify trainer + manager",
                StepStatus.CURRENT, StepActor.ERM, ActionType.MODAL,
                null,
                "Send a one-time announcement that a new intern joined. Only one trainer "
                        + "and one manager exist — this notifies them, it doesn't assign.",
                null, List.of());
    }

    private OnboardingStep buildMailAndJoining(InternLifecycle lc, boolean mailActivated,
                                                boolean joiningSet, boolean done,
                                                boolean teamNotified, boolean docsAccepted) {
        List<SubTask> subs = List.of(
                new SubTask("Company mail ID assigned + activated", mailActivated),
                new SubTask("Joining date committed", joiningSet));
        if (done) {
            return new OnboardingStep(StepId.MAIL_AND_JOINING, "Mail ID + joining date",
                    StepStatus.DONE, StepActor.ERM, ActionType.NONE,
                    null, null, null, subs);
        }
        if (!docsAccepted) {
            return new OnboardingStep(StepId.MAIL_AND_JOINING, "Mail ID + joining date",
                    StepStatus.PENDING, StepActor.ERM, ActionType.NONE,
                    null, "Available once documents are verified.", null, subs);
        }
        String help = !mailActivated && !joiningSet
                ? "Two sub-tasks: assign the intern's company mailbox AND commit a joining date."
                : !mailActivated ? "Assign the intern's company mailbox to finish this step."
                : "Commit a joining date — auto-activation will fire on that date.";
        return new OnboardingStep(StepId.MAIL_AND_JOINING, "Mail ID + joining date",
                StepStatus.CURRENT, StepActor.ERM, ActionType.MODAL,
                null, help, null, subs);
    }

    private OnboardingStep buildActivate(InternLifecycle lc, boolean active,
                                          boolean docsAssigned,
                                          boolean docsAccepted, boolean teamNotified,
                                          boolean mailAndJoiningDone) {
        if (active) {
            return new OnboardingStep(StepId.ACTIVATE, "Activate intern",
                    StepStatus.DONE, StepActor.SYSTEM, ActionType.NONE,
                    lc.getStartedAt(), null, null, List.of());
        }
        boolean allPrior = docsAssigned && docsAccepted
                && teamNotified && mailAndJoiningDone;
        if (!allPrior) {
            return new OnboardingStep(StepId.ACTIVATE, "Activate intern",
                    StepStatus.LOCKED, StepActor.SYSTEM, ActionType.GATED,
                    null,
                    "Locked — complete the earlier steps to enable activation.",
                    null, List.of());
        }
        // All gates passed. Auto-activation will fire on/after joining_date
        // (≤ 10 min after the scan). Force-activate is the escape hatch.
        return new OnboardingStep(StepId.ACTIVATE, "Activate intern",
                StepStatus.CURRENT, StepActor.SYSTEM, ActionType.MODAL,
                null,
                lc.getJoiningDate() != null
                        ? "Auto-activates on " + lc.getJoiningDate()
                            + " (next scan: ≤ 10 min). Or activate now."
                        : "Ready to activate — auto-activation needs a joining date.",
                null, List.of());
    }

    private String loadPacketStatus(UUID lifecycleId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT status FROM document_packets "
                            + "WHERE intern_lifecycle_id = ? "
                            + "ORDER BY created_at DESC LIMIT 1",
                    lifecycleId);
            if (rows.isEmpty()) return null;
            Object status = rows.get(0).get("status");
            return status == null ? null : status.toString();
        } catch (Exception e) {
            log.debug("[OnboardingTracker] packet status lookup failed: {}", e.getMessage());
            return null;
        }
    }

    private static String firstName(User u) {
        if (u == null || u.getFullName() == null) return "there";
        String[] parts = u.getFullName().trim().split("\\s+", 2);
        return parts.length > 0 && !parts[0].isEmpty() ? parts[0] : "there";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
