package com.anvicorp.api.trainer.meetings;

import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.entity.WeeklyMeeting;
import com.anvicorp.api.erm.CommunicationTemplateService;
import com.anvicorp.api.intern.OrgTeamResolver;
import com.anvicorp.api.notification.EmailProvider;
import com.anvicorp.api.notification.MeetingEmailHtmlBuilder;
import com.anvicorp.api.notification.SchedulerMeetingEmailSender;
import com.anvicorp.api.notification.UserNotificationDispatcher;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Trainer Phase 3 — weekly-meeting fan-out per doc §10. Intern always
 * receives the WEEKLY_MEETING_* email + in-app notification; evaluator,
 * manager, and ERM get an in-app cue (no email) unless the meeting is a
 * NO_SHOW, in which case ERM also receives the email so they can flag
 * repeated misses.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrainerMeetingNotificationDispatcher {

    private static final String INTERN_PATH = "/careers/intern/weekly-meetings";
    private static final String TRAINER_PATH = "/careers/trainer/weekly-meetings";

    private final UserRepository userRepository;
    private final InternLifecycleRepository lifecycleRepository;
    private final CommunicationTemplateService templateService;
    private final EmailProvider emailProvider;
    private final UserNotificationDispatcher inApp;
    private final OrgTeamResolver orgTeamResolver;
    private final SchedulerMeetingEmailSender schedulerEmail;
    private final com.anvicorp.api.notification.InternNotificationService internNotifications;
    private final com.anvicorp.api.config.BrandConfig brand;

    public void dispatchScheduled(WeeklyMeeting m, User trainer, String rescheduleNote) {
        fanOut(m, trainer, "WEEKLY_MEETING_SCHEDULED",
                "Weekly meeting scheduled",
                rescheduleNote == null
                        ? "A new meeting is on your calendar."
                        : "Your meeting was rescheduled: " + rescheduleNote,
                false);
        sendTrainerHostEmail(m, trainer);
    }

    /**
     * Email the trainer (the scheduler) with the meeting details + the
     * Webex host key so they can claim host control after joining.
     * Best-effort; failures are logged inside the sender.
     */
    private void sendTrainerHostEmail(WeeklyMeeting m, User trainer) {
        if (m == null || trainer == null
                || trainer.getEmail() == null || trainer.getEmail().isBlank()) {
            return;
        }
        InternLifecycle lc = lifecycleRepository.findById(m.getInternLifecycleId()).orElse(null);
        String internName = null;
        if (lc != null && lc.getUserId() != null) {
            internName = userRepository.findById(lc.getUserId())
                    .map(User::getFullName).orElse(null);
        }
        String participantLabel = internName != null && !internName.isBlank()
                ? "with " + internName : null;
        schedulerEmail.send(
                trainer.getEmail(),
                firstName(trainer),
                "Weekly meeting scheduled",
                nz(m.getTopic()),
                participantLabel,
                m.getScheduledFor(),
                m.getTimezone(),
                m.getZoomJoinUrl(),
                m.getZoomStartUrl(),
                m.getZoomMeetingId());
    }

    public void dispatchCompleted(WeeklyMeeting m, User trainer) {
        fanOut(m, trainer, "WEEKLY_MEETING_COMPLETED",
                "Weekly meeting completed",
                "Your trainer logged notes from the meeting.",
                false);
    }

    public void dispatchMissed(WeeklyMeeting m, User trainer, String reason) {
        fanOut(m, trainer, "WEEKLY_MEETING_MISSED",
                "Weekly meeting missed",
                reason == null || reason.isBlank()
                        ? "The scheduled meeting did not happen."
                        : "The scheduled meeting did not happen: " + reason,
                true);
    }

    public void dispatchCancelled(WeeklyMeeting m, User trainer, String reason) {
        fanOut(m, trainer, "WEEKLY_MEETING_CANCELLED",
                "Weekly meeting cancelled",
                reason == null || reason.isBlank() ? "The meeting was cancelled."
                        : "The meeting was cancelled: " + reason,
                false);
    }

    private void fanOut(WeeklyMeeting m, User trainer, String eventType,
                         String inAppTitle, String inAppBody,
                         boolean emailErm) {
        if (m == null || trainer == null) return;
        InternLifecycle lc = lifecycleRepository.findById(m.getInternLifecycleId()).orElse(null);
        if (lc == null) return;
        User intern = lc.getUserId() != null
                ? userRepository.findById(lc.getUserId()).orElse(null) : null;
        if (intern == null) return;

        String meetingDateLocal = formatLocal(m);
        String topic = nz(m.getTopic());

        // Intern — always internal mail + in-app
        // Model A — system sends; body names the trainer.
        try {
            String actorPhrase = trainer.getFullName() != null
                    && !trainer.getFullName().isBlank()
                    ? trainer.getFullName() + ", your Trainer,"
                    : "Your Trainer";
            String verb = switch (eventType) {
                case "WEEKLY_MEETING_SCHEDULED" -> "has scheduled a weekly meeting";
                case "WEEKLY_MEETING_COMPLETED" -> "logged notes from your weekly meeting";
                case "WEEKLY_MEETING_MISSED"   -> "marked your weekly meeting missed";
                case "WEEKLY_MEETING_CANCELLED" -> "cancelled your weekly meeting";
                default -> "updated your weekly meeting";
            };
            String subject = inAppTitle + " by your Trainer: " + topic;
            String plain = "Hi " + firstName(intern) + ",\n\n"
                    + actorPhrase + " " + verb + ": \"" + topic + "\"."
                    + (m.getScheduledFor() != null
                        ? "\nWhen: " + meetingDateLocal + " (" + nz(m.getTimezone()) + ")"
                        : "")
                    + (m.getZoomJoinUrl() != null && !m.getZoomJoinUrl().isBlank()
                        ? "\nJoin: " + m.getZoomJoinUrl() : "")
                    + (m.getAgenda() != null && !m.getAgenda().isBlank()
                        ? "\nAgenda: " + m.getAgenda() : "")
                    + "\n\nOpen your weekly meetings: " + INTERN_PATH
                    + "\n\n" + brand.signoff();
            internNotifications.notifyInternFromRole(intern.getId(),
                    com.anvicorp.api.notification.NotificationSenderRoles.TRAINER,
                    subject, plain, null);
            tryInApp(intern.getId(), eventType, intern.getId(),
                    inAppTitle + " by your Trainer: " + topic,
                    actorPhrase + " " + verb + ".", INTERN_PATH);
        } catch (Exception e) {
            log.warn("[TrainerMeetingNotify] intern dispatch failed: {}", e.getMessage());
        }

        // Evaluator / Manager — in-app only. Evaluator resolves
        // through OrgTeamResolver so the org-wide singleton gets the
        // cue even when the per-intern evaluator_id is null; Manager
        // stays direct (per-intern).
        for (UUID staff : new UUID[]{orgTeamResolver.resolveEvaluatorId(lc), lc.getManagerId()}) {
            if (staff == null) continue;
            tryInApp(staff, eventType, intern.getId(),
                    inAppTitle + " — " + nz(intern.getFullName()),
                    topic + " · " + meetingDateLocal, TRAINER_PATH);
        }

        // ERM — in-app, and email on NO_SHOW so the missed-meeting exception
        // surfaces in their inbox alongside the dashboard alert.
        if (lc.getErmId() != null) {
            tryInApp(lc.getErmId(), eventType, intern.getId(),
                    inAppTitle + " — " + nz(intern.getFullName()),
                    topic + " · " + meetingDateLocal, TRAINER_PATH);
            if (emailErm) {
                try {
                    User erm = userRepository.findById(lc.getErmId()).orElse(null);
                    if (erm != null && erm.getEmail() != null) {
                        Map<String, Object> vars = new LinkedHashMap<>();
                        vars.put("firstName", firstName(erm));
                        vars.put("trainerName", nz(trainer.getFullName()));
                        vars.put("meetingDateLocal", meetingDateLocal);
                        vars.put("topic", topic);
                        vars.put("internName", nz(intern.getFullName()));
                        renderAndSend(eventType, vars, erm);
                    }
                } catch (Exception e) {
                    log.warn("[TrainerMeetingNotify] ERM email failed: {}",
                            e.getMessage());
                }
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
            String subject = rendered.subject() != null ? rendered.subject() : key;
            String plain = rendered.body() != null ? rendered.body() : "";
            // Build an HTML twin with a styled Join button so the meeting
            // link is a clickable button in HTML mail clients + the internal
            // mail viewer. Plain text stays as the template rendered it for
            // text-only clients.
            Object joinUrlVar = vars.get("zoomJoinUrl");
            String joinUrl = joinUrlVar == null ? null : joinUrlVar.toString();
            String html = MeetingEmailHtmlBuilder.build(plain, joinUrl);
            emailProvider.sendBrandedHtml(recipient.getEmail(), subject, plain, html);
        } catch (Exception e) {
            log.warn("[TrainerMeetingNotify] {} render failed: {}", key, e.getMessage());
        }
    }

    private void tryInApp(UUID recipient, String eventType, UUID subjectUserId,
                           String title, String body, String url) {
        try {
            inApp.dispatch(recipient, eventType, subjectUserId, title, body, url, false);
        } catch (Exception e) {
            log.debug("[TrainerMeetingNotify] in-app to {} failed: {}",
                    recipient, e.getMessage());
        }
    }

    private static String formatLocal(WeeklyMeeting m) {
        if (m.getScheduledFor() == null) return "TBD";
        ZoneId zone = ZoneId.of(
                m.getTimezone() != null && !m.getTimezone().isBlank()
                        ? m.getTimezone() : "UTC");
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(zone).format(m.getScheduledFor());
    }

    private static String firstName(User u) {
        if (u == null || u.getFullName() == null) return "there";
        String[] parts = u.getFullName().trim().split("\\s+", 2);
        return parts.length > 0 && !parts[0].isEmpty() ? parts[0] : "there";
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
