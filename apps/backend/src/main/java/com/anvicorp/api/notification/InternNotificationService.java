package com.anvicorp.api.notification;

import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.MailHandoverState;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * One-call helper for sending a mail to an active intern (employee)
 * when a meaningful action happens. Centralised so every post-activation
 * event uses the same gate + send path.
 *
 * <h2>Gate (must pass; otherwise no send, no error)</h2>
 * <p><b>True-active</b> — {@code intern_lifecycles.active_status = 'ACTIVE'}.
 * Same signal the tracker, dashboard mode, and nav swap key off. A
 * pre-active intern (still in onboarding) gets nothing — they have
 * their own dedicated pre-active flows for offer / docs / etc.</p>
 *
 * <h2>Delivery — routing is delegated to {@link BridgingEmailProvider}</h2>
 * Calls {@link EmailProvider#sendBrandedHtml} with the intern's email
 * (which {@code users.email} still holds post-handover). The bridge
 * decides where the mail lands:
 * <ul>
 *   <li>{@code mailHandoverState = ACTIVATED} AND {@code mailAccountId != null}
 *       → company mailbox via
 *       {@code MailMessageService.deliverInternalNotification}.</li>
 *   <li>Otherwise (PENDING_ACTIVATION, PERSONAL, or missing mailbox row)
 *       → bridge falls through to raw SMTP → personal email.</li>
 * </ul>
 *
 * <p>Previously this helper also pre-gated on the mailbox state and
 * silently dropped sends when the mailbox wasn't yet ACTIVATED — which
 * meant an intern who'd just been flipped to ACTIVE but whose company
 * mailbox was still PENDING_ACTIVATION received no notification at all
 * for project assignment, KT, feedback, evaluations, etc. The mailbox
 * pre-gate is now gone; the bridge already handles routing both ways
 * and never drops a send.</p>
 *
 * <p>The send is wrapped in try/catch so a delivery failure NEVER
 * breaks the calling action.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InternNotificationService {

    private final UserRepository userRepository;
    private final InternLifecycleRepository internLifecycleRepository;
    private final EmailProvider emailProvider;

    /**
     * Send an internal mail to the intern. Silently skips when either
     * gate fails. Never throws; failures are logged at warn.
     */
    public void notifyIntern(UUID internUserId, String subject,
                              String plainBody, String htmlBody) {
        if (internUserId == null) return;
        if (subject == null || subject.isBlank()) return;
        try {
            User user = userRepository.findById(internUserId).orElse(null);
            if (user == null) {
                log.debug("[InternNotification] skip — user not found: {}", internUserId);
                return;
            }
            if (user.getEmail() == null || user.getEmail().isBlank()) {
                log.debug("[InternNotification] skip — user has no email: {}", internUserId);
                return;
            }
            InternLifecycle lc = internLifecycleRepository
                    .findByUserId(internUserId).orElse(null);
            if (lc == null || !"ACTIVE".equals(lc.getActiveStatus())) {
                log.debug("[InternNotification] skip — intern not ACTIVE: {} (status={})",
                        internUserId, lc == null ? "null" : lc.getActiveStatus());
                return;
            }
            // EmailProvider.sendBrandedHtml is wrapped by BridgingEmailProvider.
            // For ACTIVATED users with a linked mail_account it routes to the
            // company mailbox via MailMessageService.deliverInternalNotification;
            // for PENDING_ACTIVATION / PERSONAL users (or any state where the
            // mailbox isn't fully linked yet) it falls through to raw SMTP and
            // reaches the intern's personal email. Either way the message lands —
            // a notification is NEVER silently dropped just because the company
            // mailbox is still mid-provisioning. The bridge logs the routing
            // decision so the delivery channel is greppable per send.
            String safeHtml = htmlBody != null && !htmlBody.isBlank() ? htmlBody
                    : plainTextToSimpleHtml(plainBody);
            emailProvider.sendBrandedHtml(user.getEmail(), subject,
                    plainBody != null ? plainBody : subject, safeHtml);
            log.info("[InternNotification] sent '{}' to intern={} (mailbox state={})",
                    subject, internUserId, user.getMailHandoverState());
        } catch (Exception e) {
            // Notification failure is never fatal — the calling action
            // already committed; logging at warn surfaces it without
            // blocking the workflow.
            log.warn("[InternNotification] notifyIntern '{}' for {} failed (non-fatal): {}",
                    subject, internUserId, e.getMessage());
        }
    }

    /** Minimal HTML wrapper for callers that only pass plain text. */
    private static String plainTextToSimpleHtml(String plain) {
        if (plain == null || plain.isBlank()) return "<p></p>";
        return "<p>" + escape(plain).replace("\n", "<br>") + "</p>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
