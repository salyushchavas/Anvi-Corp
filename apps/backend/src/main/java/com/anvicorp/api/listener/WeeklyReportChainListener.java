package com.anvicorp.api.listener;

import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.event.WeeklyReportSubmittedEvent;
import com.anvicorp.api.event.WeeklyReportVerifiedEvent;
import com.anvicorp.api.notification.InternNotificationService;
import com.anvicorp.api.notification.NotificationSenderRoles;
import com.anvicorp.api.notification.UserNotificationDispatcher;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.UUID;

/**
 * AFTER_COMMIT chain notifications for the two-stage weekly-report flow.
 * Mirrors {@link TimesheetChainListener}:
 *
 * <ul>
 *   <li>Intern submits → notify ERM(s) — owning {@code lifecycle.erm_id}
 *       when set, else broadcast to all users with the ERM role so the
 *       report never sits unclaimed.</li>
 *   <li>ERM verifies → notify the owning {@code lifecycle.manager_id},
 *       else broadcast to MANAGER role users so the report never sits
 *       unclaimed at the approver stage.</li>
 * </ul>
 *
 * <p>Approval / return dispatches stay on {@code NotificationService}'s
 * existing intern-facing helpers ({@link
 * com.anvicorp.api.notification.NotificationService#sendWeeklyReportApproved}
 * / {@code sendWeeklyReportReturned}) — those already write both the email
 * and the in-app row. This listener only handles the NEW upstream
 * (submit→ERM, verify→Evaluator) legs.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WeeklyReportChainListener {

    private final InternLifecycleRepository lifecycleRepository;
    private final UserRepository userRepository;
    private final UserNotificationDispatcher dispatcher;
    private final InternNotificationService internNotifications;
    private final com.anvicorp.api.config.BrandConfig brand;

    // ── Submitted → ERM ─────────────────────────────────────────────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(WeeklyReportSubmittedEvent e) {
        if (e == null || e.getInternUserId() == null) return;
        try {
            String internName = nameOf(e.getInternUserId(), "An intern");
            InternLifecycle lc = lifecycleRepository.findByUserId(e.getInternUserId())
                    .orElse(null);
            List<UUID> recipients = ermRecipients(lc);
            if (recipients.isEmpty()) {
                log.debug("[WeeklyReportChain] no ERM resolvable for intern {} — submit notify skipped",
                        e.getInternUserId());
                return;
            }
            String title = internName + " submitted a weekly report";
            String body  = "Open ERM → Weekly Reports to verify the week.";
            for (UUID rid : recipients) {
                safeDispatch(rid, "WEEKLY_REPORT_SUBMITTED", e.getInternUserId(),
                        title, body, "/careers/erm/weekly-reports");
            }
            // Additive shared-inbox drop — one message into erm@ so the
            // ERM team's canonical mailbox surfaces the submit alongside
            // every other staff⇄intern conversation. Per-ERM safeDispatch
            // above still fires; this is a second channel, not a replacement.
            internNotifications.notifyStaffFromIntern(
                    NotificationSenderRoles.ERM,
                    e.getInternUserId(),
                    title,
                    body + "\n\nOpen ERM → Weekly Reports: /careers/erm/weekly-reports\n\n"
                            + brand.signoff(),
                    null);
        } catch (Exception ex) {
            log.warn("[WeeklyReportChain] onSubmitted failed (non-fatal): {}", ex.getMessage());
        }
    }

    // ── Verified → Manager ──────────────────────────────────────────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVerified(WeeklyReportVerifiedEvent e) {
        if (e == null || e.getInternUserId() == null) return;
        try {
            String internName = nameOf(e.getInternUserId(), "An intern");
            InternLifecycle lc = lifecycleRepository.findByUserId(e.getInternUserId())
                    .orElse(null);
            List<UUID> recipients = managerRecipients(lc);
            if (recipients.isEmpty()) {
                log.debug("[WeeklyReportChain] no Manager resolvable for intern {} — verify notify skipped",
                        e.getInternUserId());
                return;
            }
            String title = internName + "'s weekly report is verified";
            String body  = "Ready for your approval in Manager → Weekly Reports.";
            for (UUID rid : recipients) {
                safeDispatch(rid, "WEEKLY_REPORT_VERIFIED", e.getInternUserId(),
                        title, body, "/careers/manager/weekly-reports");
            }
        } catch (Exception ex) {
            log.warn("[WeeklyReportChain] onVerified failed (non-fatal): {}", ex.getMessage());
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private List<UUID> ermRecipients(InternLifecycle lc) {
        if (lc != null && lc.getErmId() != null) {
            return List.of(lc.getErmId());
        }
        try {
            return userRepository.findByRole(UserRole.ERM).stream()
                    .filter(u -> u != null && u.getId() != null
                            && Boolean.TRUE.equals(u.getActive()))
                    .map(User::getId)
                    .toList();
        } catch (Exception e) {
            log.debug("[WeeklyReportChain] ERM role lookup failed (non-fatal): {}", e.getMessage());
            return List.of();
        }
    }

    private List<UUID> managerRecipients(InternLifecycle lc) {
        if (lc != null && lc.getManagerId() != null) {
            return List.of(lc.getManagerId());
        }
        try {
            return userRepository.findByRole(UserRole.MANAGER).stream()
                    .filter(u -> u != null && u.getId() != null
                            && Boolean.TRUE.equals(u.getActive()))
                    .map(User::getId)
                    .toList();
        } catch (Exception e) {
            log.debug("[WeeklyReportChain] Manager role lookup failed (non-fatal): {}", e.getMessage());
            return List.of();
        }
    }

    private String nameOf(UUID userId, String fallback) {
        if (userId == null) return fallback;
        try {
            return userRepository.findById(userId)
                    .map(u -> u.getFullName() != null && !u.getFullName().isBlank()
                            ? u.getFullName() : fallback)
                    .orElse(fallback);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void safeDispatch(UUID recipient, String eventType, UUID subject,
                              String title, String body, String actionUrl) {
        if (recipient == null) return;
        try {
            dispatcher.dispatch(recipient, eventType, subject, title, body, actionUrl, false);
        } catch (Exception e) {
            log.warn("[WeeklyReportChain] dispatch {} -> {} failed (non-fatal): {}",
                    eventType, recipient, e.getMessage());
        }
    }
}
