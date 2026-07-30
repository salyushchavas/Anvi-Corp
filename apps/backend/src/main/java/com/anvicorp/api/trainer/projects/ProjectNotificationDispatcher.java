package com.anvicorp.api.trainer.projects;

import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.erm.CommunicationTemplateService;
import com.anvicorp.api.intern.OrgTeamResolver;
import com.anvicorp.api.notification.EmailProvider;
import com.anvicorp.api.notification.UserNotificationDispatcher;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Trainer Phase 2 — fans out PROJECT_ASSIGNED notifications on publish.
 *
 * <p>Per doc §10: intern receives the intern-framed PROJECT_ASSIGNED email
 * always. Evaluator + Manager + ERM receive the PROJECT_ASSIGNED_STAKEHOLDER
 * variant when the trainer left {@code notify_stakeholders_internal} ticked
 * (default). All four also receive an in-app {@link UserNotification} so
 * the dashboard inbox surfaces the event.</p>
 *
 * <p>When the assignment is backdated, every Manager and ERM in the
 * platform receives an extra in-app notification (no email) so they can
 * follow up if they didn't actually authorise the backdate.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectNotificationDispatcher {

    private static final String INTERN_DASH = "/careers/intern/projects";
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final UserRepository userRepository;
    private final CommunicationTemplateService templateService;
    private final EmailProvider emailProvider;
    private final UserNotificationDispatcher inApp;
    private final OrgTeamResolver orgTeamResolver;
    private final com.anvicorp.api.notification.InternNotificationService internNotifications;
    private final com.anvicorp.api.config.BrandConfig brand;

    public void dispatchProjectAssigned(Project project, InternLifecycle lc,
                                         User trainer, boolean notifyStakeholders,
                                         boolean backdated,
                                         String backdateAuthorizerName) {
        // Belt-and-suspenders — the assign service ALSO wraps this call
        // in try/catch, but defending here too means direct callers
        // (tests, future surfaces) can't accidentally take down a
        // domain transaction with a notify failure.
        try {
            dispatchProjectAssignedInternal(project, lc, trainer,
                    notifyStakeholders, backdated, backdateAuthorizerName);
        } catch (Exception outerErr) {
            log.error("[ProjectNotify] dispatchProjectAssigned outer body "
                            + "failed (non-fatal): {}",
                    outerErr.toString(), outerErr);
        }
    }

    private void dispatchProjectAssignedInternal(Project project, InternLifecycle lc,
                                                  User trainer, boolean notifyStakeholders,
                                                  boolean backdated,
                                                  String backdateAuthorizerName) {
        if (project == null || lc == null || trainer == null) return;

        User intern = lc.getUserId() != null
                ? userRepository.findById(lc.getUserId()).orElse(null) : null;
        if (intern == null) {
            log.warn("[ProjectNotify] no intern user for lifecycle {}; skipping fan-out",
                    lc.getId());
            return;
        }

        String dueDateLocal = project.getDueDate() != null
                ? project.getDueDate().format(DATE_FMT) : "TBD";
        String deepLinkIntern = INTERN_DASH + "/" + project.getId();
        String deepLinkStaff = "/careers/trainer/active-interns/" + lc.getId();

        // ── 1) Intern (always) ───────────────────────────────────────────
        // Model A — system sends the mail; the body names the actor + role
        // ("<Trainer name>, your Trainer, has assigned..."). Replaces the
        // previous PROJECT_ASSIGNED template render so the explicit wording
        // is the source of truth for intern delivery and lands directly in
        // the company mailbox via InternNotificationService.
        try {
            String actorPhrase = trainer.getFullName() != null
                    && !trainer.getFullName().isBlank()
                    ? trainer.getFullName() + ", your Trainer,"
                    : "Your Trainer";
            String projectTitle = nz(project.getTitle());
            String subject = "New project assigned by your Trainer: " + projectTitle;
            String plain = "Hi " + firstName(intern) + ",\n\n"
                    + actorPhrase + " has assigned you a new project: \""
                    + projectTitle + "\""
                    + (project.getDueDate() != null ? " due " + dueDateLocal : "")
                    + (project.getTechStack() != null && !project.getTechStack().isBlank()
                        ? " (tech: " + project.getTechStack() + ")" : "")
                    + ".\n\nOpen the project: " + deepLinkIntern
                    + "\n\n" + brand.signoff();
            internNotifications.notifyIntern(intern.getId(),
                    com.anvicorp.api.notification.NotificationEventType.PROJECT_ASSIGNED,
                    subject, plain, null);
            inApp.dispatch(intern.getId(), "PROJECT_ASSIGNED",
                    intern.getId(),
                    "New project assigned by your Trainer: " + projectTitle,
                    actorPhrase + " assigned a project due " + dueDateLocal + ".",
                    deepLinkIntern, true);
        } catch (Exception e) {
            log.warn("[ProjectNotify] intern dispatch failed: {}", e.getMessage());
        }

        // ── 2) Evaluator / Manager / ERM (opt-out via checkbox) ──────────
        // Evaluator resolves through OrgTeamResolver so the org-wide
        // singleton (DEFAULT_EVALUATOR_EMAIL) gets the notify even when
        // the per-intern evaluator_id was never stamped. Manager + ERM
        // stay direct (genuinely per-intern). Each null userId is also
        // handled inside sendStakeholder (early return) — belt and
        // suspenders.
        if (notifyStakeholders) {
            sendStakeholder(orgTeamResolver.resolveEvaluatorId(lc),
                    project, lc, trainer, intern, dueDateLocal, deepLinkStaff);
            sendStakeholder(lc.getManagerId(), project, lc, trainer, intern,
                    dueDateLocal, deepLinkStaff);
            sendStakeholder(lc.getErmId(), project, lc, trainer, intern,
                    dueDateLocal, deepLinkStaff);
        }

        // ── 3) Backdating broadcast — every Manager + ERM gets an in-app
        //    nudge so they can flag if they didn't actually authorise.
        if (backdated) {
            String summary = "Trainer " + nz(trainer.getFullName()) + " backdated a "
                    + "project for " + nz(intern.getFullName()) + " ("
                    + nz(project.getMonthYear()) + ") — authorizer named: "
                    + (backdateAuthorizerName != null ? backdateAuthorizerName : "n/a");
            for (User m : safeList(userRepository.findByRole(UserRole.MANAGER))) {
                if (m == null || m.getId() == null) continue;
                tryInApp(m.getId(), "PROJECT_BACKDATED",
                        intern.getId(), "Backdated project assignment",
                        summary, deepLinkStaff);
            }
            for (User m : safeList(userRepository.findByRole(UserRole.ERM))) {
                if (m == null || m.getId() == null) continue;
                tryInApp(m.getId(), "PROJECT_BACKDATED",
                        intern.getId(), "Backdated project assignment",
                        summary, deepLinkStaff);
            }
        }
    }

    private void sendStakeholder(UUID userId, Project project, InternLifecycle lc,
                                  User trainer, User intern, String dueDateLocal,
                                  String deepLinkStaff) {
        if (userId == null) return;
        try {
            User u = userRepository.findById(userId).orElse(null);
            if (u == null || u.getEmail() == null) return;
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("firstName", firstName(u));
            vars.put("trainerName", nz(trainer.getFullName()));
            vars.put("internName", nz(intern.getFullName()));
            vars.put("projectTitle", nz(project.getTitle()));
            vars.put("technologyArea", nz(project.getTechStack()));
            vars.put("dueDateLocal", dueDateLocal);
            vars.put("projectNumber", project.getProjectNumber());
            vars.put("monthYear", nz(project.getMonthYear()));
            vars.put("deepLink", deepLinkStaff);
            renderAndSend("PROJECT_ASSIGNED_STAKEHOLDER", vars, u);
            tryInApp(u.getId(), "PROJECT_ASSIGNED_STAKEHOLDER",
                    intern.getId(),
                    "Project assigned: " + intern.getFullName(),
                    project.getTitle() + " · due " + dueDateLocal,
                    deepLinkStaff);
        } catch (Exception e) {
            log.warn("[ProjectNotify] stakeholder {} dispatch failed: {}",
                    userId, e.getMessage());
        }
    }

    private void tryInApp(UUID recipient, String eventType, UUID subjectUserId,
                           String title, String body, String url) {
        try {
            inApp.dispatch(recipient, eventType, subjectUserId, title, body, url, false);
        } catch (Exception e) {
            log.warn("[ProjectNotify] in-app dispatch to {} failed: {}",
                    recipient, e.getMessage());
        }
    }

    private void renderAndSend(String templateKey, Map<String, Object> vars,
                                User recipient) {
        if (recipient == null || recipient.getEmail() == null) return;
        try {
            Optional<CommunicationTemplateService.Rendered> opt =
                    templateService.render(templateKey, "EMAIL", vars);
            if (opt.isEmpty()) {
                log.debug("[ProjectNotify] template {} missing", templateKey);
                return;
            }
            var rendered = opt.get();
            emailProvider.sendRendered(recipient.getEmail(),
                    rendered.subject() != null ? rendered.subject() : templateKey,
                    rendered.body() != null ? rendered.body() : "");
        } catch (Exception e) {
            log.warn("[ProjectNotify] renderAndSend {} failed: {}",
                    templateKey, e.getMessage());
        }
    }

    private static <T> List<T> safeList(List<T> in) {
        return in == null ? new ArrayList<>() : in;
    }

    private static String firstName(User u) {
        if (u == null || u.getFullName() == null) return "there";
        String[] parts = u.getFullName().trim().split("\\s+", 2);
        return parts.length > 0 && !parts[0].isEmpty() ? parts[0] : "there";
    }

    private static String nz(Object s) { return s == null ? "" : String.valueOf(s); }
}
