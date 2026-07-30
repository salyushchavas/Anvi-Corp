package com.anvicorp.api.listener;

import com.anvicorp.api.event.CompanyEmailAssignedEvent;
import com.anvicorp.api.notification.InternNotificationService;
import com.anvicorp.api.notification.NotificationSenderRoles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Drops the "your company mailbox is ready" welcome as the FIRST message
 * in the intern's newly-provisioned inbox. Fires AFTER_COMMIT of
 * {@code CareersMailProvisioningService.provisionForIntern}, by which
 * point {@code users.mail_account_id} + {@code mail_handover_state =
 * ACTIVATED} are set — so the bridge's G3 passes and the mail lands
 * internally.
 *
 * <p>Distinct from {@code InternActivatedListener.sendWelcome} which
 * fires later on the {@code intern_lifecycles.active_status = ACTIVE}
 * flip: mailbox provisioning normally happens BEFORE lifecycle
 * activation, so this listener uses
 * {@link InternNotificationService#notifyInternOnMailboxAssigned} —
 * the only entry point that skips the ACTIVE-lifecycle gate, because
 * the mailbox itself IS the trigger here.</p>
 *
 * <p>Sender role is ERM (mapped from
 * {@link com.anvicorp.api.notification.NotificationEventType#COMPANY_EMAIL_ASSIGNED}
 * in {@link NotificationSenderRoles}). The credentials-hand-off email
 * to the intern's personal Gmail is still sent inline by
 * {@code CareersMailProvisioningService.sendCredentialsEmail} BEFORE
 * the swap — those two messages complement each other: credentials
 * go to the personal address so the intern can log in; the welcome
 * lands in the company inbox so the very first thing they see when
 * they open /mail is a friendly hello.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CompanyEmailAssignedListener {

    private final InternNotificationService internNotifications;
    private final com.anvicorp.api.config.BrandConfig brand;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCompanyEmailAssigned(CompanyEmailAssignedEvent e) {
        if (e == null || e.getUserId() == null) return;
        try {
            String bn = brand.getName();
            String subject = "Welcome to your new " + bn + " mailbox";
            String plain = "Hi,\n\n"
                    + "Your " + bn + " company mailbox is ready. This is the first "
                    + "message in your inbox — every notification from your ERM, "
                    + "Trainer, Evaluator, and Manager will land here going forward.\n\n"
                    + "Address: " + e.getCompanyEmail() + "\n"
                    + "Sign in at: /mail\n\n"
                    + "Tip: keep an eye on this inbox for project assignments, "
                    + "KT invites, weekly meeting nudges, and evaluation prompts.\n\n"
                    + brand.signoff();
            String html = "<p>Hi,</p>"
                    + "<p>Your <strong>" + escape(bn) + " company mailbox</strong> is ready. "
                    + "This is the first message in your inbox — every notification from "
                    + "your ERM, Trainer, Evaluator, and Manager will land here going forward.</p>"
                    + "<p><strong>Address:</strong> " + escape(e.getCompanyEmail()) + "<br>"
                    + "<strong>Sign in at:</strong> <a href=\"/mail\">/mail</a></p>"
                    + "<p>Tip: keep an eye on this inbox for project assignments, KT invites, "
                    + "weekly meeting nudges, and evaluation prompts.</p>"
                    + "<p>" + escape(brand.signoff()) + "</p>";
            internNotifications.notifyInternOnMailboxAssigned(
                    e.getUserId(),
                    NotificationSenderRoles.ERM,
                    subject, plain, html);
        } catch (Exception ex) {
            log.warn("[CompanyEmailAssigned] welcome dispatch failed (non-fatal) for user {}: {}",
                    e.getUserId(), ex.getMessage());
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
