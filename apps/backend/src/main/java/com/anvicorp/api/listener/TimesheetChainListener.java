package com.anvicorp.api.listener;

import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.event.TimesheetApprovedEvent;
import com.anvicorp.api.event.TimesheetRejectedEvent;
import com.anvicorp.api.event.TimesheetSubmittedEvent;
import com.anvicorp.api.event.TimesheetVerifiedEvent;
import com.anvicorp.api.notification.InternNotificationService;
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
 * Phase B2 — AFTER_COMMIT chain notifications across the two-stage
 * timesheet approval flow:
 *
 * <ul>
 *   <li>Intern submits → notify ERM(s) — owning {@code lifecycle.erm_id}
 *       when set, else broadcast to all users with the ERM role.</li>
 *   <li>ERM verifies → notify the owning {@code lifecycle.manager_id}.</li>
 *   <li>Manager approves → notify the intern.</li>
 *   <li>Reject (at either stage) → notify the intern with the reviewer
 *       reason. {@code previousStatus} tells us whether it was an ERM
 *       reject (from SUBMITTED) or a Manager reject (from VERIFIED) so
 *       the body copy reads naturally.</li>
 * </ul>
 *
 * <p>Best-effort: each dispatch is try/catch-wrapped; a notification
 * failure never rolls back the transition (which is already committed
 * by the AFTER_COMMIT phase).</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TimesheetChainListener {

    private final InternLifecycleRepository lifecycleRepository;
    private final UserRepository userRepository;
    private final UserNotificationDispatcher dispatcher;
    private final InternNotificationService internNotifications;
    private final com.anvicorp.api.config.BrandConfig brand;

    // ── Submitted → ERM ─────────────────────────────────────────────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(TimesheetSubmittedEvent e) {
        if (e == null || e.getInternUserId() == null) return;
        try {
            String internName = nameOf(e.getInternUserId(), "An intern");
            InternLifecycle lc = lifecycleRepository.findByUserId(e.getInternUserId())
                    .orElse(null);
            List<UUID> recipients = ermRecipients(lc);
            if (recipients.isEmpty()) {
                log.debug("[TimesheetChain] no ERM resolvable for intern {} — submit notify skipped",
                        e.getInternUserId());
                return;
            }
            String title = internName + " submitted a timesheet";
            String body  = "Open ERM → Timesheets to verify the week.";
            for (UUID rid : recipients) {
                safeDispatch(rid, "TIMESHEET_SUBMITTED", e.getInternUserId(),
                        title, body, "/careers/erm/timesheets");
            }
            // Also drop ONE mail into the shared erm@ inbox (intern-as-sender,
            // erm@ mailbox as recipient) so the ERM team's canonical inbox
            // shows the submit alongside every other staff⇄intern message.
            // Per-ERM safeDispatch above still fires — this is additive so
            // an ERM whose user is linked to their personal inbox instead
            // of erm@ is never left without a channel.
            internNotifications.notifyStaffFromIntern(
                    com.anvicorp.api.notification.NotificationSenderRoles.ERM,
                    e.getInternUserId(),
                    title,
                    body + "\n\nOpen ERM → Timesheets: /careers/erm/timesheets\n\n"
                            + brand.signoff(),
                    null);
        } catch (Exception ex) {
            log.warn("[TimesheetChain] onSubmitted failed (non-fatal): {}", ex.getMessage());
        }
    }

    // ── Verified → Manager ──────────────────────────────────────────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVerified(TimesheetVerifiedEvent e) {
        if (e == null || e.getInternUserId() == null) return;
        try {
            String internName = nameOf(e.getInternUserId(), "An intern");
            InternLifecycle lc = lifecycleRepository.findByUserId(e.getInternUserId())
                    .orElse(null);
            if (lc == null || lc.getManagerId() == null) {
                log.debug("[TimesheetChain] no manager on lifecycle for {} — verify notify skipped",
                        e.getInternUserId());
                return;
            }
            String title = internName + "'s timesheet is verified";
            String body  = "Ready for your approval in Manager → Timesheet Approvals.";
            safeDispatch(lc.getManagerId(), "TIMESHEET_VERIFIED", e.getInternUserId(),
                    title, body, "/careers/manager/timesheet-approvals");
            // Lifecycle-mail-bridge: also confirm ERM verification to the
            // intern via the company mailbox from erm@. Previously only the
            // Manager was notified (in-app); the intern had no visibility
            // into the intermediate verification step.
            try {
                String actorPhrase = resolveActorPhrase(e.getActorUserId(), "ERM");
                String subject = "Your timesheet was verified by your ERM";
                String mailBody = actorPhrase + " has verified your timesheet. "
                        + "It's now with your Manager for approval — you don't "
                        + "need to do anything further at this step."
                        + "\n\nOpen your timesheets: /careers/intern/timesheets"
                        + "\n\n" + brand.signoff();
                internNotifications.notifyIntern(e.getInternUserId(),
                        com.anvicorp.api.notification.NotificationEventType.TIMESHEET_VERIFIED_BY_ERM,
                        subject, mailBody, null);
            } catch (Exception ex2) {
                log.warn("[TimesheetChain] verified intern-mail failed (non-fatal): {}",
                        ex2.getMessage());
            }
        } catch (Exception ex) {
            log.warn("[TimesheetChain] onVerified failed (non-fatal): {}", ex.getMessage());
        }
    }

    // ── Approved → Intern ───────────────────────────────────────────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onApproved(TimesheetApprovedEvent e) {
        if (e == null || e.getInternUserId() == null) return;
        try {
            // Actor + role per Model A. Approval happens at the Manager stage
            // (Manager promotes a VERIFIED row → APPROVED), so the actor's
            // role label is "Manager". Fall back to neutral "Your Manager"
            // when actor isn't resolvable.
            String actorPhrase = resolveActorPhrase(e.getActorUserId(), "Manager");
            String body = actorPhrase + " has approved your timesheet. "
                    + "Your week is now counted toward your total hours.";
            safeDispatch(e.getInternUserId(), "TIMESHEET_APPROVED", e.getInternUserId(),
                    "Your Manager approved your timesheet", body,
                    "/careers/intern/timesheets");
            // Phase: Employee internal-mail — also land in the intern's
            // company mailbox. Helper short-circuits when intern isn't
            // ACTIVE / mailbox isn't ACTIVATED, so this is safe to call
            // unconditionally.
            internNotifications.notifyInternFromRole(e.getInternUserId(),
                    com.anvicorp.api.notification.NotificationSenderRoles.MANAGER,
                    "Your Manager approved your timesheet",
                    body
                    + "\n\nOpen your timesheets: /careers/intern/timesheets\n\n" + brand.signoff(),
                    null);
        } catch (Exception ex) {
            log.warn("[TimesheetChain] onApproved failed (non-fatal): {}", ex.getMessage());
        }
    }

    // ── Rejected → Intern ───────────────────────────────────────────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRejected(TimesheetRejectedEvent e) {
        if (e == null || e.getInternUserId() == null) return;
        try {
            // Actor + role per Model A. Reject can fire at either the ERM
            // stage (SUBMITTED → REJECTED) or the Manager stage
            // (VERIFIED → REJECTED); pick the role label from
            // previousStatus and resolve the acting user's name.
            String roleWord = "VERIFIED".equalsIgnoreCase(e.getPreviousStatus())
                    ? "Manager" : "ERM";
            String actorPhrase = resolveActorPhrase(e.getActorUserId(), roleWord);
            String reason = e.getReason() != null ? e.getReason() : "(no reason given)";
            String body = actorPhrase + " sent your week back for correction. Reason: "
                    + truncate(reason, 200);
            String subject = "Timesheet returned for correction by your " + roleWord;
            safeDispatch(e.getInternUserId(), "TIMESHEET_REJECTED", e.getInternUserId(),
                    subject, body,
                    "/careers/intern/timesheets");
            // Phase: Employee internal-mail — same body, also delivered
            // to the company mailbox. Helper gates on active+ACTIVATED.
            // Sender role tracks who returned it (ERM at first stage,
            // Manager at the second) so the intern's mailbox shows the
            // right "from" address.
            String senderRole = "VERIFIED".equalsIgnoreCase(e.getPreviousStatus())
                    ? com.anvicorp.api.notification.NotificationSenderRoles.MANAGER
                    : com.anvicorp.api.notification.NotificationSenderRoles.ERM;
            internNotifications.notifyInternFromRole(e.getInternUserId(),
                    senderRole,
                    subject,
                    body + "\n\nOpen your timesheets: /careers/intern/timesheets\n\n" + brand.signoff(),
                    null);
        } catch (Exception ex) {
            log.warn("[TimesheetChain] onRejected failed (non-fatal): {}", ex.getMessage());
        }
    }

    /**
     * Resolve an actor's "<Name>, your <Role>," prefix for the body text
     * (Model A — sender stays the system, the body names who acted).
     * Falls back to "Your <Role>" when the actor user isn't resolvable.
     */
    private String resolveActorPhrase(java.util.UUID actorUserId, String roleWord) {
        if (actorUserId != null) {
            User actor = userRepository.findById(actorUserId).orElse(null);
            if (actor != null && actor.getFullName() != null
                    && !actor.getFullName().isBlank()) {
                return actor.getFullName() + ", your " + roleWord + ",";
            }
        }
        return "Your " + roleWord;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private List<UUID> ermRecipients(InternLifecycle lc) {
        if (lc != null && lc.getErmId() != null) {
            return List.of(lc.getErmId());
        }
        // No per-intern ERM owner — broadcast to all active ERMs so the
        // week doesn't sit unclaimed.
        try {
            return userRepository.findByRole(UserRole.ERM).stream()
                    .filter(u -> u != null && u.getId() != null
                            && Boolean.TRUE.equals(u.getActive()))
                    .map(User::getId)
                    .toList();
        } catch (Exception e) {
            log.debug("[TimesheetChain] ERM role lookup failed (non-fatal): {}", e.getMessage());
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
            log.warn("[TimesheetChain] dispatch {} -> {} failed (non-fatal): {}",
                    eventType, recipient, e.getMessage());
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
