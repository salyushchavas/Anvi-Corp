package com.anvicorp.api.notification;

import com.anvicorp.api.entity.DoubtRequest;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.repository.ProjectRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Doubt-session feature — 4-event fan-out.
 *
 * <ol>
 *   <li>{@code DOUBT_RAISED} — trainer gets in-app cue.</li>
 *   <li>{@code DOUBT_REPLIED} — intern gets in-app + internal mail
 *       (Model A actor naming).</li>
 *   <li>{@code DOUBT_SESSION_SCHEDULED} — intern gets in-app +
 *       internal mail with the join link.</li>
 *   <li>{@code DOUBT_RESOLVED} — intern gets in-app + internal mail.</li>
 * </ol>
 *
 * Every send is try/catch wrapped — a notification failure never
 * breaks the domain action.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DoubtNotificationDispatcher {

    private static final String INTERN_DOUBTS = "/careers/intern/doubts";
    private static final String TRAINER_DOUBTS = "/careers/trainer/doubts";

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final UserNotificationDispatcher inApp;
    private final InternNotificationService internNotifications;
    private final com.anvicorp.api.config.BrandConfig brand;

    // ── 1. Doubt raised → trainer in-app ────────────────────────────────

    public void dispatchRaised(DoubtRequest d) {
        if (d == null) return;
        try {
            String internName = nameOf(d.getInternUserId(), "An intern");
            String projectTitle = projectTitleOf(d.getProjectId());
            String title = internName + " raised a doubt"
                    + (projectTitle != null ? " on " + projectTitle : "");
            String body = truncate(d.getText(), 200);
            inApp.dispatch(d.getTrainerUserId(), "DOUBT_RAISED",
                    d.getInternUserId(), title, body, TRAINER_DOUBTS, false);
        } catch (Exception e) {
            log.warn("[DoubtNotify] raised dispatch failed (non-fatal): {}", e.getMessage());
        }
    }

    // ── 2. Trainer reply → intern in-app + internal mail ────────────────

    public void dispatchReplied(DoubtRequest d, User trainer) {
        if (d == null) return;
        String firstName = firstNameOf(d.getInternUserId());
        String actorPhrase = actorPhrase(trainer);
        String projectTitle = projectTitleOf(d.getProjectId());
        String reply = d.getTrainerReply() != null ? d.getTrainerReply() : "";
        String subject = "Reply from your Trainer on your doubt"
                + (projectTitle != null ? ": " + projectTitle : "");
        String plain = "Hi " + firstName + ",\n\n"
                + actorPhrase + " replied to your doubt"
                + (projectTitle != null ? " on \"" + projectTitle + "\"" : "")
                + ":\n\n" + reply
                + "\n\nOpen your doubts: " + INTERN_DOUBTS + "/" + d.getId()
                + "\n\n" + brand.signoff();
        try {
            inApp.dispatch(d.getInternUserId(), "DOUBT_REPLIED",
                    d.getInternUserId(), subject, truncate(reply, 200),
                    INTERN_DOUBTS + "/" + d.getId(), true);
        } catch (Exception e) {
            log.debug("[DoubtNotify] replied in-app failed: {}", e.getMessage());
        }
        try {
            internNotifications.notifyInternFromRole(d.getInternUserId(),
                    NotificationSenderRoles.TRAINER, subject, plain, null);
        } catch (Exception e) {
            log.warn("[DoubtNotify] replied intern-mail failed (non-fatal): {}",
                    e.getMessage());
        }
    }

    // ── 3. Trainer scheduled session → intern in-app + mail w/ join ─────

    public void dispatchSessionScheduled(DoubtRequest d, User trainer) {
        if (d == null) return;
        String firstName = firstNameOf(d.getInternUserId());
        String actorPhrase = actorPhrase(trainer);
        String projectTitle = projectTitleOf(d.getProjectId());
        String when = formatLocal(d);
        String tz = d.getSessionTimezone() != null && !d.getSessionTimezone().isBlank()
                ? d.getSessionTimezone() : "UTC";
        String joinUrl = d.getZoomJoinUrl();
        String subject = "Doubt session scheduled by your Trainer"
                + (projectTitle != null ? ": " + projectTitle : "");
        String plain = "Hi " + firstName + ",\n\n"
                + actorPhrase + " has scheduled a live doubt-clearing session"
                + (projectTitle != null ? " on \"" + projectTitle + "\"" : "")
                + " for " + when + " (" + tz + ")."
                + (joinUrl != null && !joinUrl.isBlank()
                    ? "\n\nJoin: " + joinUrl : "")
                + "\n\nOpen your doubts: " + INTERN_DOUBTS + "/" + d.getId()
                + "\n\n" + brand.signoff();
        try {
            inApp.dispatch(d.getInternUserId(), "DOUBT_SESSION_SCHEDULED",
                    d.getInternUserId(), subject,
                    "Live session " + when + " (" + tz + ")",
                    INTERN_DOUBTS + "/" + d.getId(), true);
        } catch (Exception e) {
            log.debug("[DoubtNotify] session in-app failed: {}", e.getMessage());
        }
        try {
            internNotifications.notifyInternFromRole(d.getInternUserId(),
                    NotificationSenderRoles.TRAINER, subject, plain, null);
        } catch (Exception e) {
            log.warn("[DoubtNotify] session intern-mail failed (non-fatal): {}",
                    e.getMessage());
        }
    }

    // ── 4. Resolved → intern in-app + internal mail ─────────────────────

    public void dispatchResolved(DoubtRequest d, User actor) {
        if (d == null) return;
        String firstName = firstNameOf(d.getInternUserId());
        String actorPhrase = actorPhrase(actor);
        String projectTitle = projectTitleOf(d.getProjectId());
        String subject = "Your doubt has been marked resolved";
        String plain = "Hi " + firstName + ",\n\n"
                + actorPhrase + " has marked your doubt"
                + (projectTitle != null ? " on \"" + projectTitle + "\"" : "")
                + " resolved. If you're still stuck, raise a new doubt."
                + "\n\nOpen your doubts: " + INTERN_DOUBTS + "/" + d.getId()
                + "\n\n" + brand.signoff();
        try {
            inApp.dispatch(d.getInternUserId(), "DOUBT_RESOLVED",
                    d.getInternUserId(), subject,
                    "Doubt marked resolved.",
                    INTERN_DOUBTS + "/" + d.getId(), true);
        } catch (Exception e) {
            log.debug("[DoubtNotify] resolved in-app failed: {}", e.getMessage());
        }
        try {
            internNotifications.notifyInternFromRole(d.getInternUserId(),
                    NotificationSenderRoles.TRAINER, subject, plain, null);
        } catch (Exception e) {
            log.warn("[DoubtNotify] resolved intern-mail failed (non-fatal): {}",
                    e.getMessage());
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private String nameOf(UUID userId, String fallback) {
        if (userId == null) return fallback;
        try {
            return userRepository.findById(userId)
                    .map(u -> u.getFullName() != null && !u.getFullName().isBlank()
                            ? u.getFullName() : fallback)
                    .orElse(fallback);
        } catch (Exception ignored) { return fallback; }
    }

    private String firstNameOf(UUID userId) {
        if (userId == null) return "there";
        try {
            return userRepository.findById(userId)
                    .map(u -> u.getFullName() != null && !u.getFullName().isBlank()
                            ? u.getFullName().trim().split("\\s+", 2)[0]
                            : "there")
                    .orElse("there");
        } catch (Exception ignored) { return "there"; }
    }

    private String projectTitleOf(UUID projectId) {
        if (projectId == null) return null;
        try {
            return projectRepository.findById(projectId)
                    .map(Project::getTitle).orElse(null);
        } catch (Exception ignored) { return null; }
    }

    /** Model A — "<Name>, your Trainer," or fallback "Your Trainer". */
    private static String actorPhrase(User actor) {
        if (actor != null && actor.getFullName() != null
                && !actor.getFullName().isBlank()) {
            return actor.getFullName() + ", your Trainer,";
        }
        return "Your Trainer";
    }

    private static String formatLocal(DoubtRequest d) {
        if (d.getSessionScheduledFor() == null) return "TBD";
        ZoneId zone = ZoneId.of(d.getSessionTimezone() != null
                && !d.getSessionTimezone().isBlank()
                ? d.getSessionTimezone() : "UTC");
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(zone).format(d.getSessionScheduledFor());
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
