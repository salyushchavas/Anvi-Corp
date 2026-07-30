package com.anvicorp.api.listener;

import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.event.InternActivatedEvent;
import com.anvicorp.api.intern.OrgTeamResolver;
import com.anvicorp.api.notification.InternNotificationService;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Fires the two activation-day internal mails the intern receives when
 * they become an employee:
 *   1. <b>Welcome</b> — short, warm "you're officially active".
 *   2. <b>Team introduction</b> — names + emails of their trainer and
 *      manager so they know who their points of contact are.
 *
 * <p>Listens for {@link InternActivatedEvent} (published by
 * {@code InternActivationJob.activateOneWithActor} after the
 * activeStatus = ACTIVE flip commits). Runs on AFTER_COMMIT so the
 * intern's own row is guaranteed visible by the time the helper
 * re-reads it for the {@code activeStatus = 'ACTIVE'} gate.</p>
 *
 * <p>Idempotency: the event is published once per activation transaction
 * (the activate path early-exits when the lifecycle status isn't
 * ONBOARDING_ACCEPTED), so this listener fires exactly once per
 * intern transition. The helper's own delivery is wrapped in
 * try/catch — a transient mail failure won't block re-activation
 * runs.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InternActivatedListener {

    private final InternNotificationService internNotifications;
    private final UserRepository userRepository;
    private final InternLifecycleRepository internLifecycleRepository;
    private final OrgTeamResolver orgTeamResolver;
    private final com.anvicorp.api.config.BrandConfig brand;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onActivated(InternActivatedEvent e) {
        if (e == null || e.getUserId() == null) return;
        UUID internUserId = e.getUserId();
        try {
            User intern = userRepository.findById(internUserId).orElse(null);
            if (intern == null) return;
            sendWelcome(intern);
            sendTeamIntroduction(intern);
        } catch (Exception ex) {
            log.warn("[InternActivated] notification fan-out failed (non-fatal): {}",
                    ex.getMessage());
        }
    }

    private void sendWelcome(User intern) {
        String first = firstName(intern);
        String bn = brand.getName();
        String subject = "Welcome to " + bn + ", " + first + " — you're active";
        String plain = "Hi " + first + ",\n\n"
                + "You're now active in the " + bn + " system as an employee. "
                + "Your dashboard is ready, and over the next few days "
                + "you'll receive notifications about your project assignment, "
                + "KT session, weekly meeting, and evaluations.\n\n"
                + "Open your " + bn + " dashboard: /careers/intern\n\n"
                + "Welcome aboard.\n"
                + brand.signoff();
        String html = "<p>Hi <strong>" + escape(first) + "</strong>,</p>"
                + "<p>You're now <strong>active in the " + escape(bn) + " system as an employee</strong>. "
                + "Your dashboard is ready, and over the next few days you'll receive "
                + "notifications about your project assignment, KT session, weekly meeting, "
                + "and evaluations.</p>"
                + "<p><a href=\"/careers/intern\">Open your " + escape(bn) + " dashboard</a></p>"
                + "<p>Welcome aboard.<br>" + escape(brand.signoff()) + "</p>";
        internNotifications.notifyInternFromRole(intern.getId(),
                com.anvicorp.api.notification.NotificationSenderRoles.ERM,
                subject, plain, html);
    }

    private void sendTeamIntroduction(User intern) {
        InternLifecycle lc = internLifecycleRepository
                .findByUserId(intern.getId()).orElse(null);
        if (lc == null) return;
        User trainer = orgTeamResolver.resolveTrainer(lc).orElse(null);
        User manager = lc.getManagerId() != null
                ? userRepository.findById(lc.getManagerId()).orElse(null)
                : null;
        String first = firstName(intern);
        String trainerLine = trainer != null
                ? (nz(trainer.getFullName()) + " — " + nz(trainer.getEmail()))
                : "(not assigned yet)";
        String managerLine = manager != null
                ? (nz(manager.getFullName()) + " — " + nz(manager.getEmail()))
                : "(not assigned yet)";
        String subject = "Meet your team — your Trainer and Manager";
        String bn = brand.getName();
        String plain = "Hi " + first + ",\n\n"
                + "Your day-to-day points of contact at " + bn + ":\n\n"
                + "Trainer: " + trainerLine + "\n"
                + "Manager: " + managerLine + "\n\n"
                + "Your Trainer guides your project work and weekly KT sessions. "
                + "Your Manager owns timesheet approvals and any operational "
                + "escalations. Feel free to reach out to either directly via "
                + "the " + bn + " mailbox.\n\n"
                + brand.signoff();
        String html = "<p>Hi <strong>" + escape(first) + "</strong>,</p>"
                + "<p>Your day-to-day points of contact at " + escape(bn) + ":</p>"
                + "<ul>"
                + "<li><strong>Trainer:</strong> " + escape(trainerLine) + "</li>"
                + "<li><strong>Manager:</strong> " + escape(managerLine) + "</li>"
                + "</ul>"
                + "<p>Your Trainer guides your project work and weekly KT sessions. "
                + "Your Manager owns timesheet approvals and any operational escalations. "
                + "Feel free to reach out to either directly via the " + escape(bn) + " mailbox.</p>"
                + "<p>" + escape(brand.signoff()) + "</p>";
        internNotifications.notifyInternFromRole(intern.getId(),
                com.anvicorp.api.notification.NotificationSenderRoles.ERM,
                subject, plain, html);
    }

    private static String firstName(User u) {
        if (u == null || u.getFullName() == null) return "there";
        String[] parts = u.getFullName().trim().split("\\s+", 2);
        return parts.length > 0 && !parts[0].isEmpty() ? parts[0] : "there";
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
