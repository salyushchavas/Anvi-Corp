package com.anvicorp.api.event.project.listener;

import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.event.project.ProjectCompletedEvent;
import com.anvicorp.api.event.project.ProjectMarkedPendingVivaEvent;
import com.anvicorp.api.event.project.ProjectReturnedForRevisionsEvent;
import com.anvicorp.api.event.project.ProjectSubmittedEvent;
import com.anvicorp.api.event.project.ProjectTechApprovedEvent;
import com.anvicorp.api.notification.InternNotificationService;
import com.anvicorp.api.notification.NotificationService;
import com.anvicorp.api.repository.ProjectRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Fans out emails for the two-role workflow transitions.
 *
 * <p>Listens on {@code AFTER_COMMIT} so a rollback in
 * {@code ProjectWorkflowService} never leaves the user with a stale email
 * about a transition that didn't actually land. Each handler re-reads the
 * project by id inside its own (REQUIRES_NEW) transaction via
 * {@link NotificationService}, so a listener failure can never poison the
 * upstream transaction.</p>
 *
 * <p>Best-effort end-to-end: any send failure is caught + logged; the
 * domain transition has already committed and downstream state is
 * unaffected.</p>
 *
 * <p>The {@link NotificationService} typed-method sends bypass the
 * {@code BridgingEmailProvider} and land in the intern's personal Gmail —
 * so for ACTIVE interns we ALSO call {@link InternNotificationService#notifyIntern}
 * which routes through the bridge to their company mailbox. Pre-active
 * interns skip the internal-mail path silently (gated inside notifyIntern).</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailNotifierListener {

    private final ProjectRepository projectRepository;
    private final NotificationService notificationService;
    private final InternNotificationService internNotifications;
    private final UserRepository userRepository;
    private final com.anvicorp.api.config.BrandConfig brand;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(ProjectSubmittedEvent event) {
        if (event == null) return;
        try {
            Project project = projectRepository.findByIdWithGraph(event.getProjectId())
                    .orElse(null);
            if (project == null) return;
            // Reuses the existing supervisor-notification send (legacy
            // ProjectService.submit calls this inline; the new workspace
            // submit flow routes through this listener instead so the
            // event-driven pattern owns the side effect end-to-end).
            notificationService.sendProjectSubmitted(project);
        } catch (Exception e) {
            log.warn("PROJECT_SUBMITTED email failed (non-fatal) for {}: {}",
                    event.getProjectId(), e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTechApproved(ProjectTechApprovedEvent event) {
        if (event == null) return;
        try {
            Project project = projectRepository.findByIdWithGraph(event.getProjectId())
                    .orElse(null);
            if (project == null) return;
            notificationService.sendProjectTechApproved(project);
            notifyInternInternalMail(project, event.getApproverUserId(),
                    "Trainer",
                    com.anvicorp.api.notification.NotificationSenderRoles.TRAINER,
                    "Your project '" + safeTitle(project) + "' passed technical review",
                    actorPhrase -> actorPhrase + " has approved your project \""
                            + safeTitle(project) + "\". Tech review complete — your "
                            + "Reporting Manager will schedule the viva next.");
        } catch (Exception e) {
            log.warn("PROJECT_TECH_APPROVED email failed (non-fatal) for {}: {}",
                    event.getProjectId(), e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReturnedForRevisions(ProjectReturnedForRevisionsEvent event) {
        if (event == null) return;
        try {
            Project project = projectRepository.findByIdWithGraph(event.getProjectId())
                    .orElse(null);
            if (project == null) return;
            notificationService.sendProjectReturnedForRevisions(project, event.getReason());
            String reasonLine = event.getReason() != null && !event.getReason().isBlank()
                    ? " Reason: " + event.getReason() : "";
            notifyInternInternalMail(project, event.getReviewerUserId(),
                    "Reviewer",
                    com.anvicorp.api.notification.NotificationSenderRoles.TRAINER,
                    "Project '" + safeTitle(project) + "' returned for revisions",
                    actorPhrase -> actorPhrase + " returned \"" + safeTitle(project)
                            + "\" for changes." + reasonLine
                            + " Iterate and re-submit when ready.");
        } catch (Exception e) {
            log.warn("PROJECT_RETURNED_FOR_REVISIONS email failed (non-fatal) for {}: {}",
                    event.getProjectId(), e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMarkedPendingViva(ProjectMarkedPendingVivaEvent event) {
        if (event == null) return;
        try {
            Project project = projectRepository.findByIdWithGraph(event.getProjectId())
                    .orElse(null);
            if (project == null) return;
            notificationService.sendProjectPendingViva(project);
            notifyInternInternalMail(project, event.getReportingManagerUserId(),
                    "Reporting Manager",
                    com.anvicorp.api.notification.NotificationSenderRoles.MANAGER,
                    "Your project '" + safeTitle(project) + "' is ready for viva",
                    actorPhrase -> actorPhrase + " marked \"" + safeTitle(project)
                            + "\" ready for the viva. Watch your inbox for the "
                            + "schedule and prepare to present.");
        } catch (Exception e) {
            log.warn("PROJECT_PENDING_VIVA email failed (non-fatal) for {}: {}",
                    event.getProjectId(), e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCompleted(ProjectCompletedEvent event) {
        if (event == null) return;
        try {
            Project project = projectRepository.findByIdWithGraph(event.getProjectId())
                    .orElse(null);
            if (project == null) return;
            // Both ProjectService.complete (legacy, TE actor) and
            // ProjectWorkflowService.completeAfterViva (two-role, RM actor)
            // publish ProjectCompletedEvent. Resolve the actor's primary
            // role dynamically so wording matches the flow that fired.
            String roleWord = resolveRoleWord(event.getClosedByUserId(), "Reporting Manager");
            // Legacy TE actor → Trainer mailbox; RM actor → Manager mailbox.
            String senderRole = "Trainer".equals(roleWord)
                    ? com.anvicorp.api.notification.NotificationSenderRoles.TRAINER
                    : com.anvicorp.api.notification.NotificationSenderRoles.MANAGER;
            notifyInternInternalMail(project, event.getClosedByUserId(),
                    roleWord,
                    senderRole,
                    "Project '" + safeTitle(project) + "' completed",
                    actorPhrase -> actorPhrase + " has marked \"" + safeTitle(project)
                            + "\" complete. Nice work — your post-project "
                            + "evaluation will follow shortly.");
        } catch (Exception e) {
            log.warn("PROJECT_COMPLETED internal-mail failed (non-fatal) for {}: {}",
                    event.getProjectId(), e.getMessage());
        }
    }

    /**
     * Compose + send a Model A internal-mail to the active intern. Gated on
     * ACTIVE+ACTIVATED inside {@link InternNotificationService#notifyIntern}
     * so calling for pre-active interns is a no-op (no error). The actor
     * role label is the caller's responsibility (TE/Trainer for
     * tech-approval + first-stage return; RM/Reporting Manager for
     * pending-viva + completed).
     */
    private void notifyInternInternalMail(Project project, UUID actorUserId,
                                           String roleWord, String senderLocalPart,
                                           String subject,
                                           java.util.function.Function<String, String> bodyBuilder) {
        UUID internUserId = internUserId(project);
        if (internUserId == null) return;
        String actorPhrase = resolveActorPhrase(actorUserId, roleWord);
        String body = "Hi,\n\n" + bodyBuilder.apply(actorPhrase)
                + "\n\nOpen your projects: /careers/intern/projects"
                + "\n\n" + brand.signoff();
        internNotifications.notifyInternFromRole(internUserId, senderLocalPart,
                subject, body, null);
    }

    private UUID internUserId(Project project) {
        Candidate intern = project.getIntern();
        if (intern == null || intern.getUser() == null) return null;
        return intern.getUser().getId();
    }

    private String resolveActorPhrase(UUID actorUserId, String roleWord) {
        if (actorUserId != null) {
            try {
                User actor = userRepository.findById(actorUserId).orElse(null);
                if (actor != null && actor.getFullName() != null
                        && !actor.getFullName().isBlank()) {
                    return actor.getFullName() + ", your " + roleWord + ",";
                }
            } catch (Exception ignored) { }
        }
        return "Your " + roleWord;
    }

    /**
     * Pick a friendly role label from the actor's primary role. Used by
     * onCompleted where both the legacy TE-driven path and the two-role
     * RM-driven path publish the same event and need different wording.
     */
    private String resolveRoleWord(UUID actorUserId, String fallback) {
        if (actorUserId == null) return fallback;
        try {
            User actor = userRepository.findById(actorUserId).orElse(null);
            if (actor == null || actor.getRoles() == null || actor.getRoles().isEmpty()) {
                return fallback;
            }
            return switch (actor.getRoles().iterator().next()) {
                case TRAINER -> "Trainer";
                case REPORTING_MANAGER -> "Reporting Manager";
                case MANAGER -> "Manager";
                case ERM -> "ERM";
                case SUPER_ADMIN -> "Admin";
                default -> fallback;
            };
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String safeTitle(Project p) {
        return p == null || p.getTitle() == null || p.getTitle().isBlank()
                ? "your project" : p.getTitle();
    }
}
