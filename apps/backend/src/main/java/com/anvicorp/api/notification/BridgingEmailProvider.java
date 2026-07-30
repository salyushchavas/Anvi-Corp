package com.anvicorp.api.notification;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.MailHandoverState;
import com.anvicorp.api.mail.entity.MailAccount;
import com.anvicorp.api.mail.entity.MailDomain;
import com.anvicorp.api.mail.repository.MailAccountRepository;
import com.anvicorp.api.mail.repository.MailDomainRepository;
import com.anvicorp.api.mail.service.MailMessageService;
import com.anvicorp.api.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Wraps the real {@link SmtpEmailProvider} so a notification whose
 * {@link NotificationSenderContext sender-role context} is a staff mailbox
 * (evaluator@/erm@/trainer@/manager@) AND whose recipient is a
 * fully-activated intern (User row has {@code mail_account_id} set AND
 * {@code mail_handover_state = ACTIVATED}) lands in the intern's INTERNAL
 * company mailbox — sent FROM the acting staff's mailbox — instead of the
 * intern's personal Gmail via SMTP.
 *
 * <h2>Routing decision — only two of ~30 methods bridge</h2>
 * The bridge only intercepts {@link #sendRendered} and {@link #sendBrandedHtml}
 * because those are the two generic seams the notification layer already uses
 * for staff→intern messages ({@code InternNotificationService.notifyIntern}
 * and {@code UserNotificationDispatcher.tryEmailHook}). Every other typed
 * method (verification codes, hiring-lifecycle mails, work-auth reminders,
 * etc.) delegates straight to SMTP — those flows either don't have a staff
 * "acting mailbox," go to pre-active applicants who have no internal mailbox,
 * or are strictly system/automated with a {@code noreply@} sender.
 *
 * <h2>Guards (fall through to SMTP if any fails)</h2>
 * <ol>
 *   <li>{@code NotificationSenderContext.get()} must be non-null and not
 *       {@code noreply} — this is the "acting staff" signal.</li>
 *   <li>Recipient email must resolve to a {@link User} row (best-effort).</li>
 *   <li>Recipient user must have {@code mailAccountId != null} AND
 *       {@code mailHandoverState = ACTIVATED} — mailbox provisioned AND
 *       ERM/user has confirmed handover.</li>
 *   <li>Staff mailbox must resolve from local-part + default domain.</li>
 *   <li>Recipient's {@link MailAccount} row must still exist.</li>
 * </ol>
 * Any failure = SMTP fallback. Never silent-drop; never throw past the
 * caller (email failures never break the calling business action).
 *
 * <h2>Domain</h2>
 * Both sender and recipient resolve on the DEFAULT domain (the first ACTIVE
 * one, or the seeded "anvicorp.com"). Multi-domain deployments today only
 * have one active domain — if that ever changes, add an explicit
 * "system domain" config knob.
 */
@Slf4j
public class BridgingEmailProvider implements EmailProvider {

    private final EmailProvider delegate;
    private final UserRepository userRepository;
    private final MailAccountRepository mailAccountRepository;
    private final MailDomainRepository mailDomainRepository;
    private final MailMessageService mailMessageService;

    public BridgingEmailProvider(EmailProvider delegate,
                                  UserRepository userRepository,
                                  MailAccountRepository mailAccountRepository,
                                  MailDomainRepository mailDomainRepository,
                                  MailMessageService mailMessageService) {
        this.delegate = delegate;
        this.userRepository = userRepository;
        this.mailAccountRepository = mailAccountRepository;
        this.mailDomainRepository = mailDomainRepository;
        this.mailMessageService = mailMessageService;
    }

    // ── Bridged seams ────────────────────────────────────────────────────

    @Override
    public void sendRendered(String email, String subject, String body) {
        if (tryInternalSend(email, subject, body, null)) return;
        delegate.sendRendered(email, subject, body);
    }

    @Override
    public void sendBrandedHtml(String email, String subject,
                                 String plainBody, String htmlBody) {
        if (tryInternalSend(email, subject, plainBody, htmlBody)) return;
        delegate.sendBrandedHtml(email, subject, plainBody, htmlBody);
    }

    /**
     * @return {@code true} if the message was delivered internally (caller
     *         should NOT also SMTP-send); {@code false} if any guard failed
     *         and the caller must fall through to SMTP.
     */
    private boolean tryInternalSend(String recipientEmail, String subject,
                                     String plainBody, String htmlBody) {
        try {
            if (recipientEmail == null || recipientEmail.isBlank()) return false;
            if (subject == null || subject.isBlank()) return false;

            String senderLocalPart = NotificationSenderContext.get();
            if (senderLocalPart == null
                    || senderLocalPart.equalsIgnoreCase(NotificationSenderRoles.NOREPLY)) {
                // G1 — no acting-staff context (or noreply). Expected for
                // system-fired flows; logged at debug so it doesn't spam.
                log.debug("[MailBridge] G1 skip — no sender context (or noreply) for '{}' to={}",
                        subject, recipientEmail);
                return false;
            }

            User recipient = userRepository.findByEmail(recipientEmail.trim()).orElse(null);
            if (recipient == null) {
                log.debug("[MailBridge] G2 skip — recipient email {} not found in users", recipientEmail);
                return false;
            }

            if (recipient.getMailAccountId() == null
                    || recipient.getMailHandoverState() != MailHandoverState.ACTIVATED) {
                // G3 — recipient's company mailbox isn't fully handed over.
                // Raised to warn: this was silent-no-log before, which hid
                // "empty inbox after ACTIVATED" bugs. Now a per-message
                // trace shows exactly why the bridge bounced.
                log.warn("[MailBridge] G3 skip — recipient {} not ready (mail_account_id={} handover={}) — SMTP fallback",
                        recipientEmail, recipient.getMailAccountId(), recipient.getMailHandoverState());
                return false;
            }

            MailDomain domain = resolveDefaultDomain();
            if (domain == null) {
                log.warn("[MailBridge] G4 skip — no default domain 'anvicorp.com' — SMTP fallback");
                return false;
            }

            Optional<MailAccount> senderOpt = mailAccountRepository
                    .findByLocalPartAndDomain_Id(senderLocalPart, domain.getId());
            if (senderOpt.isEmpty()) {
                // G5 — the FROM mailbox itself doesn't exist. Raised to
                // warn: this is the exact class of failure that was
                // dropping every List-2A staff-to-intern email silently
                // when the role mailboxes weren't provisioned.
                log.warn("[MailBridge] G5 skip — sender mailbox {}@{} not provisioned in mail_accounts "
                                + "— run MailRoleAccountSeeder or provision manually. SMTP fallback.",
                        senderLocalPart, domain.getName());
                return false;
            }
            Optional<MailAccount> recipientOpt = mailAccountRepository
                    .findById(recipient.getMailAccountId());
            if (recipientOpt.isEmpty()) {
                log.warn("[MailBridge] G6 skip — recipient's mail_account_id {} points to no row — SMTP fallback",
                        recipient.getMailAccountId());
                return false;
            }

            mailMessageService.deliverInternalNotification(
                    senderOpt.get(), recipientOpt.get(),
                    subject, plainBody, htmlBody);
            log.info("[MailBridge] delivered internally from={}@{} to={} subject='{}'",
                    senderLocalPart, domain.getName(),
                    recipientOpt.get().getLocalPart() + "@" + domain.getName(),
                    subject);
            return true;
        } catch (Exception e) {
            log.warn("[MailBridge] internal delivery failed for {} — falling back to SMTP: {}",
                    recipientEmail, e.getMessage());
            return false;
        }
    }

    private MailDomain resolveDefaultDomain() {
        return mailDomainRepository.findByName("anvicorp.com").orElse(null);
    }

    // ── Passthrough (never bridged) ──────────────────────────────────────

    @Override
    public void sendVerificationCode(String email, String code, Instant expiresAt) {
        delegate.sendVerificationCode(email, code, expiresAt);
    }

    @Override
    public void sendApplicantIdIssued(String email, String applicantId) {
        delegate.sendApplicantIdIssued(email, applicantId);
    }

    @Override
    public void sendPasswordReset(String email, String code, Instant expiresAt) {
        delegate.sendPasswordReset(email, code, expiresAt);
    }

    @Override
    public void sendConditionalSelectionConfirmation(String email, String jobPostingTitle, String entityName) {
        delegate.sendConditionalSelectionConfirmation(email, jobPostingTitle, entityName);
    }

    @Override
    public void sendApplicationReceived(String email, String candidateName, String jobTitle, String entityName) {
        delegate.sendApplicationReceived(email, candidateName, jobTitle, entityName);
    }

    @Override
    public void sendApplicationShortlisted(String email, String candidateName, String jobTitle, String entityName) {
        delegate.sendApplicationShortlisted(email, candidateName, jobTitle, entityName);
    }

    @Override
    public void sendApplicationRejected(String email, String candidateName, String jobTitle, String entityName) {
        delegate.sendApplicationRejected(email, candidateName, jobTitle, entityName);
    }

    @Override
    public void sendInterviewScheduled(String email, String candidateName, String jobTitle, String entityName,
                                        Instant scheduledAt, Integer durationMinutes, String interviewType,
                                        String interviewerName, String meetingUrl, String candidateNotes) {
        delegate.sendInterviewScheduled(email, candidateName, jobTitle, entityName,
                scheduledAt, durationMinutes, interviewType, interviewerName, meetingUrl, candidateNotes);
    }

    @Override
    public void sendInterviewReminder(String email, String candidateName, String jobTitle, String entityName,
                                       Instant scheduledAt, Integer durationMinutes, String interviewType,
                                       String interviewerName, String meetingUrl) {
        delegate.sendInterviewReminder(email, candidateName, jobTitle, entityName,
                scheduledAt, durationMinutes, interviewType, interviewerName, meetingUrl);
    }

    @Override
    public void sendOfferExtended(String email, String candidateName, String jobTitle, String entityName,
                                   BigDecimal compensationAmount, String compensationCurrency,
                                   String compensationFrequency, LocalDate startDate,
                                   Instant expiresAt, String viewOfferUrl) {
        delegate.sendOfferExtended(email, candidateName, jobTitle, entityName,
                compensationAmount, compensationCurrency, compensationFrequency,
                startDate, expiresAt, viewOfferUrl);
    }

    @Override
    public void sendOfferAccepted(String email, String candidateName, String jobTitle,
                                   String entityName, LocalDate startDate) {
        delegate.sendOfferAccepted(email, candidateName, jobTitle, entityName, startDate);
    }

    @Override
    public void sendOfferAcceptedToOps(String opsEmail, String candidateName, String candidateEmail,
                                        String jobTitle, String entityName, LocalDate startDate) {
        delegate.sendOfferAcceptedToOps(opsEmail, candidateName, candidateEmail,
                jobTitle, entityName, startDate);
    }

    @Override
    public void sendOnboardingWelcome(String email, String internName, String jobTitle,
                                       String entityName, LocalDate startDate, String dashboardUrl) {
        delegate.sendOnboardingWelcome(email, internName, jobTitle, entityName, startDate, dashboardUrl);
    }

    @Override
    public void sendI9Section1Reminder(String email, String internName,
                                        LocalDate section1DueDate, String dashboardUrl) {
        delegate.sendI9Section1Reminder(email, internName, section1DueDate, dashboardUrl);
    }

    @Override
    public void sendI9Section2Pending(String hrEmail, String internName,
                                       LocalDate section2DueDate, String hrDashboardUrl) {
        delegate.sendI9Section2Pending(hrEmail, internName, section2DueDate, hrDashboardUrl);
    }

    @Override
    public void sendI983PlanNeeded(String email, String internName, String dashboardUrl) {
        delegate.sendI983PlanNeeded(email, internName, dashboardUrl);
    }

    @Override
    public void sendI983PlanReady(String hrEmail, String internName, String hrDashboardUrl) {
        delegate.sendI983PlanReady(hrEmail, internName, hrDashboardUrl);
    }

    @Override
    public void sendEVerifyCaseOpened(String email, String internName, String dashboardUrl) {
        delegate.sendEVerifyCaseOpened(email, internName, dashboardUrl);
    }

    @Override
    public void sendEVerifyTncAlert(String email, String internName, String dashboardUrl) {
        delegate.sendEVerifyTncAlert(email, internName, dashboardUrl);
    }

    @Override
    public void sendEVerifyCleared(String email, String internName, String dashboardUrl) {
        delegate.sendEVerifyCleared(email, internName, dashboardUrl);
    }

    @Override
    public void sendWorkAuthExpiryReminder(String email, String internName, int daysUntilExpiry,
                                            LocalDate expirationDate, String authType, String dashboardUrl) {
        delegate.sendWorkAuthExpiryReminder(email, internName, daysUntilExpiry,
                expirationDate, authType, dashboardUrl);
    }

    @Override
    public void sendComplianceTaskReminder(String email, String internName, String taskTitle,
                                            LocalDate dueDate, Integer daysOverdue, String dashboardUrl) {
        delegate.sendComplianceTaskReminder(email, internName, taskTitle, dueDate,
                daysOverdue, dashboardUrl);
    }

    @Override
    public void sendWeeklyReportDue(String email, String internName,
                                     LocalDate weekStart, String dashboardUrl) {
        delegate.sendWeeklyReportDue(email, internName, weekStart, dashboardUrl);
    }

    @Override
    public void sendWeeklyReportReturned(String email, String internName, LocalDate weekStart,
                                          String reviewNotes, String dashboardUrl) {
        delegate.sendWeeklyReportReturned(email, internName, weekStart, reviewNotes, dashboardUrl);
    }

    @Override
    public void sendWeeklyReportApproved(String email, String internName,
                                          LocalDate weekStart, String dashboardUrl) {
        delegate.sendWeeklyReportApproved(email, internName, weekStart, dashboardUrl);
    }

    @Override
    public void sendTimesheetDue(String email, String internName,
                                  LocalDate weekStart, String dashboardUrl) {
        delegate.sendTimesheetDue(email, internName, weekStart, dashboardUrl);
    }

    @Override
    public void sendProjectAssigned(String email, String internName, String projectTitle,
                                     LocalDate dueDate, String supervisorName, String dashboardUrl) {
        delegate.sendProjectAssigned(email, internName, projectTitle, dueDate,
                supervisorName, dashboardUrl);
    }

    @Override
    public void sendProjectSubmitted(String supervisorEmail, String supervisorName, String internName,
                                      String projectTitle, String supervisorDashboardUrl) {
        delegate.sendProjectSubmitted(supervisorEmail, supervisorName, internName,
                projectTitle, supervisorDashboardUrl);
    }

    @Override
    public void sendProjectReturned(String email, String internName, String projectTitle,
                                     String reviewNotes, String dashboardUrl) {
        delegate.sendProjectReturned(email, internName, projectTitle, reviewNotes, dashboardUrl);
    }

    @Override
    public void sendProjectCompleted(String email, String internName, String projectTitle,
                                      String dashboardUrl) {
        delegate.sendProjectCompleted(email, internName, projectTitle, dashboardUrl);
    }

    @Override
    public void sendEvaluationDue(String supervisorEmail, String supervisorName, String internName,
                                   String evaluationType, Integer daysInDraft,
                                   String supervisorDashboardUrl) {
        delegate.sendEvaluationDue(supervisorEmail, supervisorName, internName, evaluationType,
                daysInDraft, supervisorDashboardUrl);
    }

    @Override
    public void sendEvaluationFinalized(String email, String internName, String evaluationType,
                                         String supervisorName, Integer overallRating,
                                         String dashboardUrl) {
        delegate.sendEvaluationFinalized(email, internName, evaluationType, supervisorName,
                overallRating, dashboardUrl);
    }

    @Override
    public void sendI983SelfEvalDue(String email, String internName, String evaluationType,
                                     String dashboardUrl) {
        delegate.sendI983SelfEvalDue(email, internName, evaluationType, dashboardUrl);
    }

    @Override
    public void sendProjectTechApproved(String email, String internName, String projectTitle,
                                         String dashboardUrl) {
        delegate.sendProjectTechApproved(email, internName, projectTitle, dashboardUrl);
    }

    @Override
    public void sendProjectReturnedForRevisions(String email, String internName, String projectTitle,
                                                 String reason, String dashboardUrl) {
        delegate.sendProjectReturnedForRevisions(email, internName, projectTitle, reason, dashboardUrl);
    }

    @Override
    public void sendProjectPendingViva(String email, String internName, String projectTitle,
                                        String dashboardUrl) {
        delegate.sendProjectPendingViva(email, internName, projectTitle, dashboardUrl);
    }
}
