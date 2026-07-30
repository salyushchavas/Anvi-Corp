package com.anvicorp.api.trainer.reviews;

import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.entity.ProjectSubmission;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.erm.CommunicationTemplateService;
import com.anvicorp.api.intern.OrgTeamResolver;
import com.anvicorp.api.notification.EmailProvider;
import com.anvicorp.api.notification.UserNotificationDispatcher;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Trainer Phase 3 — fan-out for the doc §9 Feedback Form decisions.
 * The intern always receives the FEEDBACK_PUBLISHED email + in-app
 * notification when the trainer commits a decision (ACCEPT /
 * REQUEST_REVISION / ESCALATE). Evaluator + Manager + ERM get an in-app
 * cue; ERM additionally receives an in-app PROJECT_ESCALATED notification
 * when the decision is ESCALATE so the new
 * {@link com.anvicorp.api.entity.ExceptionRecord} doesn't get lost.
 *
 * <p>NO_ACTION_YET dispatches nothing — silent state for trainer
 * follow-up.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrainerFeedbackNotificationDispatcher {

    private static final String INTERN_PROJECTS = "/careers/intern/projects";
    private static final String TRAINER_REVIEWS = "/careers/trainer/pending-reviews";
    private static final String EVALUATOR_PENDING = "/careers/evaluator/pending-vivas";
    private static final String ERM_EXCEPTIONS = "/careers/erm/exception-records";

    private final UserRepository userRepository;
    private final InternLifecycleRepository lifecycleRepository;
    private final CommunicationTemplateService templateService;
    private final EmailProvider emailProvider;
    private final UserNotificationDispatcher inApp;
    private final OrgTeamResolver orgTeamResolver;
    private final com.anvicorp.api.notification.InternNotificationService internNotifications;
    private final com.anvicorp.api.config.BrandConfig brand;

    public void dispatchFeedbackPublished(Project project,
                                           ProjectSubmission submission,
                                           User trainer, String decision) {
        if (project == null || trainer == null) return;
        InternLifecycle lc = project.getInternLifecycleId() != null
                ? lifecycleRepository.findById(project.getInternLifecycleId())
                        .orElse(null) : null;
        if (lc == null || lc.getUserId() == null) return;
        User intern = userRepository.findById(lc.getUserId()).orElse(null);
        if (intern == null) return;

        String label = humanLabel(decision);
        String dl = INTERN_PROJECTS + "/" + project.getId();

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("firstName", firstName(intern));
        vars.put("trainerName", nz(trainer.getFullName()));
        vars.put("projectTitle", nz(project.getTitle()));
        vars.put("decisionLabel", label);
        vars.put("technicalScore", submission != null
                ? submission.getTechnicalScore() : null);
        vars.put("communicationScore", submission != null
                ? submission.getCommunicationScore() : null);
        vars.put("reviewNotes", submission != null
                ? nz(submission.getTrainerFeedback()) : "");
        vars.put("nextActionBlurb", nextActionText(
                submission != null ? submission.getNextAction() : null));
        vars.put("deepLink", dl);

        // ── Intern (always) ──────────────────────────────────────────────
        // Model A — system sends; body names the trainer who published.
        // ACCEPT now means "approved + routed to evaluator for Q&A" (the
        // trainer no longer completes the project), so the intern-facing
        // copy explicitly says the project is heading to evaluation.
        try {
            String actorPhrase = trainer.getFullName() != null
                    && !trainer.getFullName().isBlank()
                    ? trainer.getFullName() + ", your Trainer,"
                    : "Your Trainer";
            String projectTitle = nz(project.getTitle());
            String reviewNotes = submission != null && submission.getTrainerFeedback() != null
                    ? submission.getTrainerFeedback() : "";
            boolean isAccept = "ACCEPT".equals(decision);
            String subject = isAccept
                    ? "Project approved by your Trainer — going to evaluation: " + projectTitle
                    : "Project feedback by your Trainer: " + projectTitle;
            String openingLine = isAccept
                    ? actorPhrase + " has approved your project \""
                            + projectTitle
                            + "\". It's now scheduled for an Evaluator Q&A session — "
                            + "the Evaluator will reach out with a meeting time and "
                            + "sign final completion after the session."
                    : actorPhrase + " has shared feedback on your project \""
                            + projectTitle + "\": " + label + ".";
            String plain = "Hi " + firstName(intern) + ",\n\n"
                    + openingLine
                    + (!reviewNotes.isBlank() ? "\n\nNotes: " + reviewNotes : "")
                    + "\n\nOpen the project: " + dl
                    + "\n\n" + brand.signoff();
            internNotifications.notifyInternFromRole(intern.getId(),
                    com.anvicorp.api.notification.NotificationSenderRoles.TRAINER,
                    subject, plain, null);
            inApp.dispatch(intern.getId(), "FEEDBACK_PUBLISHED",
                    intern.getId(),
                    subject,
                    actorPhrase + " " + label
                        + (submission != null && submission.getTrainerFeedback() != null
                            ? " — " + truncate(submission.getTrainerFeedback(), 120) : ""),
                    dl, true);
        } catch (Exception e) {
            log.warn("[TrainerFeedbackNotify] intern dispatch failed: {}",
                    e.getMessage());
        }

        // ── Evaluator (auto-route on ACCEPT) ──────────────────────────────
        // On ACCEPT the project's status is now PENDING_VIVA — auto-route
        // to the evaluator's queue with a Q&A-specific subject + deep link
        // to the project so the evaluator can schedule the session
        // immediately. Other decisions keep the existing trainer-cue
        // posture (informational only, no action required).
        UUID evaluatorId = orgTeamResolver.resolveEvaluatorId(lc);
        if (evaluatorId != null) {
            if ("ACCEPT".equals(decision)) {
                String evalTitle = "Q&A session needed — " + intern.getFullName();
                String evalBody = "Project \"" + nz(project.getTitle())
                        + "\" was approved by " + nz(trainer.getFullName())
                        + " and is awaiting your Q&A session + final approval.";
                tryInApp(evaluatorId, "PROJECT_PENDING_VIVA", intern.getId(),
                        evalTitle, evalBody, EVALUATOR_PENDING);
            } else {
                tryInApp(evaluatorId, "FEEDBACK_PUBLISHED", intern.getId(),
                        label + " — " + intern.getFullName(),
                        project.getTitle(), TRAINER_REVIEWS);
            }
        }
        // Manager cue stays direct and informational on every decision.
        if (lc.getManagerId() != null) {
            tryInApp(lc.getManagerId(), "FEEDBACK_PUBLISHED", intern.getId(),
                    label + " — " + intern.getFullName(),
                    project.getTitle(), TRAINER_REVIEWS);
        }

        // ── ERM (in-app cue + ESCALATE-only flag) ────────────────────────
        if (lc.getErmId() != null) {
            tryInApp(lc.getErmId(), "FEEDBACK_PUBLISHED", intern.getId(),
                    label + " — " + intern.getFullName(),
                    project.getTitle(), TRAINER_REVIEWS);
            if ("ESCALATE".equals(decision)) {
                tryInApp(lc.getErmId(), "PROJECT_ESCALATED", intern.getId(),
                        "Project escalation — " + intern.getFullName(),
                        "Trainer flagged " + project.getTitle()
                                + ". Open the Exceptions dashboard for context.",
                        ERM_EXCEPTIONS);
            }
        }
    }

    private void renderAndSend(String key, Map<String, Object> vars, User recipient) {
        if (recipient == null || recipient.getEmail() == null) return;
        try {
            Optional<CommunicationTemplateService.Rendered> opt =
                    templateService.render(key, "EMAIL", vars);
            if (opt.isEmpty()) return;
            var rendered = opt.get();
            emailProvider.sendRendered(recipient.getEmail(),
                    rendered.subject() != null ? rendered.subject() : key,
                    rendered.body() != null ? rendered.body() : "");
        } catch (Exception e) {
            log.warn("[TrainerFeedbackNotify] {} render failed: {}", key, e.getMessage());
        }
    }

    private void tryInApp(UUID recipient, String eventType, UUID subjectUserId,
                           String title, String body, String url) {
        try {
            inApp.dispatch(recipient, eventType, subjectUserId, title, body, url, false);
        } catch (Exception e) {
            log.debug("[TrainerFeedbackNotify] in-app to {} failed: {}",
                    recipient, e.getMessage());
        }
    }

    private static String humanLabel(String decision) {
        if (decision == null) return "Feedback";
        return switch (decision) {
            case "ACCEPT" -> "Approved — Q&A pending";
            case "REQUEST_REVISION" -> "Revision requested";
            case "ESCALATE" -> "Escalated";
            case "NO_ACTION_YET" -> "Reviewed (no action yet)";
            default -> "Feedback";
        };
    }

    private static String nextActionText(String code) {
        if (code == null || code.isBlank()) return "";
        return switch (code.toUpperCase()) {
            case "REVISION" -> "Next action: revise + re-submit.";
            case "NEXT_PROJECT" -> "Next action: move on to the next project.";
            case "EXTRA_TRAINING" -> "Next action: extra training assigned.";
            case "ESCALATION" -> "Next action: escalation in progress.";
            default -> "";
        };
    }

    private static String firstName(User u) {
        if (u == null || u.getFullName() == null) return "there";
        String[] parts = u.getFullName().trim().split("\\s+", 2);
        return parts.length > 0 && !parts[0].isEmpty() ? parts[0] : "there";
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
