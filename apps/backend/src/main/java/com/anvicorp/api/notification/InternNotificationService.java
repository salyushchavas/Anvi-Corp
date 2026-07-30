package com.anvicorp.api.notification;

import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.MailHandoverState;
import com.anvicorp.api.mail.entity.MailAccount;
import com.anvicorp.api.mail.entity.MailDomain;
import com.anvicorp.api.mail.repository.MailAccountRepository;
import com.anvicorp.api.mail.repository.MailDomainRepository;
import com.anvicorp.api.mail.service.MailMessageService;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
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
     * Direct-write dependencies for the intern→staff-role-mailbox path
     * ({@link #notifyStaffFromIntern}). That path bypasses the
     * {@link EmailProvider}/{@link BridgingEmailProvider} chain because the
     * bridge is built for {@code String email → User row → user.mailAccountId}
     * resolution — role mailboxes (erm@/trainer@/…) don't have {@code User}
     * rows, so G2 skips them. We resolve sender + recipient
     * {@link MailAccount}s explicitly here and call
     * {@link MailMessageService#deliverInternalNotification} straight (which
     * commits in REQUIRES_NEW so it can't be rolled back by any outer tx).
     */
    private final MailMessageService mailMessageService;
    private final MailAccountRepository mailAccountRepository;
    private final MailDomainRepository mailDomainRepository;

    /** Same default the bridge uses ({@code resolveDefaultDomain}). */
    private static final String DEFAULT_DOMAIN_NAME = "anvicorp.com";

    /**
     * Overload that stamps the sender-role context from the supplied
     * {@code eventType} for the duration of the send. Use this from any
     * dispatcher that knows the notification's business event — the bridge
     * (see {@link BridgingEmailProvider}) reads the context to decide the
     * "from" mailbox on the internal-mail leg. When {@code eventType} is
     * {@code null} OR maps to {@code noreply} (per
     * {@link NotificationSenderRoles#forEvent}), the send behaves exactly
     * like the base {@link #notifyIntern(UUID, String, String, String)}.
     *
     * <p>The context is set-and-cleared strictly around the send so a
     * Tomcat-pooled thread cannot leak the role into an unrelated
     * request.</p>
     */
    public void notifyIntern(UUID internUserId, NotificationEventType eventType,
                              String subject, String plainBody, String htmlBody) {
        String role = NotificationSenderRoles.forEvent(eventType);
        notifyInternFromRole(internUserId, role, subject, plainBody, htmlBody);
    }

    /**
     * Same contract as {@link #notifyIntern(UUID, NotificationEventType, String,
     * String, String)} but takes the sender-role local-part directly. Use for
     * events that have no {@link NotificationEventType} value (e.g. the
     * trainer's session scheduling, ERM/manager timesheet review, group
     * sessions) — the caller supplies the appropriate role constant from
     * {@link NotificationSenderRoles} (e.g. {@code TRAINER}, {@code ERM},
     * {@code EVALUATOR}, {@code MANAGER}).
     */
    public void notifyInternFromRole(UUID internUserId, String senderLocalPart,
                                      String subject, String plainBody, String htmlBody) {
        NotificationSenderContext.set(senderLocalPart != null
                ? senderLocalPart : NotificationSenderRoles.DEFAULT_LOCAL_PART);
        try {
            notifyIntern(internUserId, subject, plainBody, htmlBody);
        } finally {
            NotificationSenderContext.clear();
        }
    }

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

    /**
     * Send an internal welcome to an intern whose company mailbox was
     * JUST provisioned — the very first message that lands in their
     * new inbox. Bypasses the {@code intern_lifecycles.active_status =
     * 'ACTIVE'} gate that {@link #notifyIntern} enforces, because at
     * mailbox-provisioning time the intern is typically NOT yet
     * lifecycle-ACTIVE (activation happens later, via
     * {@code InternActivationJob}) — but the mailbox itself is ready
     * ({@code mailAccountId} set + {@code mailHandoverState = ACTIVATED}
     * by {@link com.anvicorp.api.mail.service.CareersMailProvisioningService})
     * so the bridge's G3 will pass.
     *
     * <p>Only path in this class that lets you deliver to a pre-active
     * intern; use it exclusively for the mailbox-just-assigned welcome.
     * Every other post-onboarding intern-facing send should keep going
     * through {@link #notifyIntern} / {@link #notifyInternFromRole} so
     * the ACTIVE gate stays intact.</p>
     */
    public void notifyInternOnMailboxAssigned(UUID internUserId, String senderLocalPart,
                                               String subject, String plainBody, String htmlBody) {
        if (internUserId == null) return;
        if (subject == null || subject.isBlank()) return;
        try {
            User user = userRepository.findById(internUserId).orElse(null);
            if (user == null) {
                log.debug("[InternNotification/mbx] skip — user not found: {}", internUserId);
                return;
            }
            if (user.getEmail() == null || user.getEmail().isBlank()) {
                log.debug("[InternNotification/mbx] skip — user has no email: {}", internUserId);
                return;
            }
            NotificationSenderContext.set(senderLocalPart != null
                    ? senderLocalPart : NotificationSenderRoles.DEFAULT_LOCAL_PART);
            try {
                String safeHtml = htmlBody != null && !htmlBody.isBlank() ? htmlBody
                        : plainTextToSimpleHtml(plainBody);
                emailProvider.sendBrandedHtml(user.getEmail(), subject,
                        plainBody != null ? plainBody : subject, safeHtml);
                log.info("[InternNotification/mbx] sent '{}' to intern={} (mailbox state={})",
                        subject, internUserId, user.getMailHandoverState());
            } finally {
                NotificationSenderContext.clear();
            }
        } catch (Exception e) {
            log.warn("[InternNotification/mbx] notifyInternOnMailboxAssigned '{}' for {} "
                            + "failed (non-fatal): {}",
                    subject, internUserId, e.getMessage());
        }
    }

    /**
     * Intern → staff-role shared mailbox delivery (e.g. an intern submits
     * a weekly report / timesheet → ERM's shared inbox at
     * {@code erm@anvicorp.com}). Complement of {@link #notifyIntern}: same
     * on-the-wire mail row, opposite direction.
     *
     * <p><b>Why not go through the bridge?</b> The bridge resolves the
     * recipient with {@code userRepository.findByEmail(recipientEmail)} +
     * {@code recipient.getMailAccountId()}. Role mailboxes have NO
     * {@link User} row (they're pure {@link MailAccount} rows seeded by
     * {@code MailRoleAccountSeeder}), so G2 skips every role-addressed
     * send. This helper resolves the role mailbox directly by
     * {@code (localPart, domain)}, exactly like the bridge's G5 sender
     * lookup does for staff-as-sender.</p>
     *
     * <p><b>Sender is the intern's own mailbox</b> — the ERM team's inbox
     * shows {@code arunr@anvicorp.com → erm@anvicorp.com}, which is the
     * "single canonical inbox of the working relationship" the operator
     * asked for. Silently no-ops if the intern isn't ACTIVATED (no
     * personal mailbox to send FROM); the parallel per-ERM
     * {@code UserNotificationDispatcher.dispatch} path still delivers
     * the in-app notification + a personal-email hook so ERM is never
     * left in the dark.</p>
     *
     * @param staffRoleLocalPart e.g. {@code "erm"}, {@code "trainer"},
     *                           {@code "evaluator"}, {@code "manager"}
     *                           — the role mailbox that receives the mail.
     * @param internUserId       the intern who acted (submitted the report,
     *                           uploaded the doc, etc.). Their mailbox is
     *                           the sender.
     * @param subject            subject line for the internal mail row.
     * @param plainBody          plain-text body. Required.
     * @param htmlBody           optional HTML body (nullable).
     */
    public void notifyStaffFromIntern(String staffRoleLocalPart, UUID internUserId,
                                       String subject, String plainBody, String htmlBody) {
        if (staffRoleLocalPart == null || staffRoleLocalPart.isBlank()) return;
        if (internUserId == null) return;
        if (subject == null || subject.isBlank()) return;
        try {
            User intern = userRepository.findById(internUserId).orElse(null);
            if (intern == null) {
                log.debug("[InternNotification/staff] skip — intern user not found: {}", internUserId);
                return;
            }
            if (intern.getMailAccountId() == null
                    || intern.getMailHandoverState() != MailHandoverState.ACTIVATED) {
                // The intern doesn't have their company mailbox yet, so
                // there's no sender to route from. The parallel per-ERM
                // in-app + SMTP-hook dispatch already ran; the ERM will
                // still see the alert on their personal channel.
                log.debug("[InternNotification/staff] skip — intern {} mailbox not ACTIVATED "
                                + "(mail_account_id={} handover={})",
                        internUserId, intern.getMailAccountId(), intern.getMailHandoverState());
                return;
            }
            Optional<MailAccount> senderOpt = mailAccountRepository
                    .findById(intern.getMailAccountId());
            if (senderOpt.isEmpty()) {
                log.warn("[InternNotification/staff] intern {} mail_account_id {} "
                                + "points at no row — cannot send",
                        internUserId, intern.getMailAccountId());
                return;
            }
            MailDomain domain = mailDomainRepository.findByName(DEFAULT_DOMAIN_NAME).orElse(null);
            if (domain == null) {
                log.warn("[InternNotification/staff] no default domain '{}' — skipping send",
                        DEFAULT_DOMAIN_NAME);
                return;
            }
            String role = staffRoleLocalPart.trim().toLowerCase(java.util.Locale.ROOT);
            Optional<MailAccount> roleMailboxOpt = mailAccountRepository
                    .findByLocalPartAndDomain_Id(role, domain.getId());
            if (roleMailboxOpt.isEmpty()) {
                log.warn("[InternNotification/staff] role mailbox {}@{} not provisioned — "
                                + "run MailRoleAccountSeeder or provision manually",
                        role, domain.getName());
                return;
            }
            mailMessageService.deliverInternalNotification(
                    senderOpt.get(), roleMailboxOpt.get(),
                    subject, plainBody, htmlBody);
            log.info("[InternNotification/staff] delivered intern→{}@{} from={}@{} subject='{}'",
                    role, domain.getName(),
                    senderOpt.get().getLocalPart(), domain.getName(), subject);
        } catch (Exception e) {
            log.warn("[InternNotification/staff] notifyStaffFromIntern '{}' role={} intern={} "
                            + "failed (non-fatal): {}",
                    subject, staffRoleLocalPart, internUserId, e.getMessage());
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
