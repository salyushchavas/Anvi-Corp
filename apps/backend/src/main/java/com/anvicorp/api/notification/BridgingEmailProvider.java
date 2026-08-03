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
        if (suppressExternalForActivated(email, subject, "sendRendered")) return;
        delegate.sendRendered(email, subject, body);
    }

    @Override
    public void sendBrandedHtml(String email, String subject,
                                 String plainBody, String htmlBody) {
        if (tryInternalSend(email, subject, plainBody, htmlBody)) return;
        if (suppressExternalForActivated(email, subject, "sendBrandedHtml")) return;
        delegate.sendBrandedHtml(email, subject, plainBody, htmlBody);
    }

    // ── Central gate ─────────────────────────────────────────────────────

    /**
     * True when the recipient resolves to a user whose mailbox is
     * {@link MailHandoverState#ACTIVATED} — the "internal-only" cohort.
     * Cheap best-effort lookup; a repository failure returns {@code false}
     * (fail-open on the gate, fail-closed on the guard — never crash a
     * send path).
     */
    private boolean isActivatedUser(String email) {
        if (email == null || email.isBlank()) return false;
        try {
            User u = userRepository.findByEmail(email.trim()).orElse(null);
            return u != null
                    && u.getMailAccountId() != null
                    && u.getMailHandoverState() == MailHandoverState.ACTIVATED;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Central "no external send to ACTIVATED intern" gate. Called after
     * {@link #tryInternalSend} has returned {@code false} (guard failure
     * or delivery exception) and before the SMTP fall-through.
     *
     * <p>Suppresses the external send when the recipient is an ACTIVATED
     * user and the send is NOT tagged as account-security
     * ({@link SecurityMailContext#isSecurity()}). Emits a loud WARN so the
     * suppression is greppable per message — the operator can then
     * confirm the intern still received the in-app notification.</p>
     *
     * <p>For non-activated recipients (applicants, PENDING_ACTIVATION
     * interns) this returns {@code false} → SMTP proceeds unchanged.</p>
     *
     * @return {@code true} if the external send MUST be suppressed;
     *         {@code false} to proceed with the SMTP delegate.
     */
    private boolean suppressExternalForActivated(String email, String subject, String seam) {
        if (SecurityMailContext.isSecurity()) return false;
        if (!isActivatedUser(email)) return false;
        log.warn("[MailBridge] SUPPRESSED external send to ACTIVATED intern={} "
                        + "seam={} subject='{}' — internal delivery failed. "
                        + "In-app notification still fires; no external Gmail leak.",
                email, seam, subject);
        return true;
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

            // deliverInternalNotification runs in its own REQUIRES_NEW tx,
            // so by the time this call returns the mail_messages +
            // mail_mailbox_entries rows are COMMITTED — the log below can
            // truthfully claim persistence. Under the previous default
            // REQUIRED propagation, this log fired mid-caller-tx and lied
            // whenever the caller (or its afterCommit host) later rolled
            // back — the "delivered" log stayed but the rows vanished.
            mailMessageService.deliverInternalNotification(
                    senderOpt.get(), recipientOpt.get(),
                    subject, plainBody, htmlBody);
            log.info("[MailBridge] delivered internally (committed) from={}@{} to={} subject='{}'",
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
    //
    // Every typed method below is a raw delegate. The three security-adjacent
    // sends (sendVerificationCode, sendPasswordReset, sendApplicantIdIssued)
    // pass straight through — an ACTIVATED user who forgot their password or
    // is verifying a NEW registration MUST still reach the external inbox
    // (they can't sign in to see internal mail without it). Every other typed
    // send that could reach an intern email address goes through
    // {@link #gateThenDelegate}: for ACTIVATED recipients the SMTP call is
    // suppressed (WARN logged) so lifecycle mail cannot leak externally.
    // Staff-recipient sends (sendI9Section2Pending, sendI983PlanReady,
    // sendOfferAcceptedToOps, sendEvaluationDue, sendProjectSubmitted) share
    // the same gate — a NO-OP for staff since they aren't ACTIVATED interns.

    /** Runs the SMTP delegate unless the recipient is an ACTIVATED user. */
    private void gateThenDelegate(String email, String subject, String seam, Runnable delegateSend) {
        if (suppressExternalForActivated(email, subject, seam)) return;
        delegateSend.run();
    }

    // ── Security-exempt (always external) ────────────────────────────────

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

    // ── Applicant / candidate lifecycle (pre-activation, gated for safety) ─

    @Override
    public void sendConditionalSelectionConfirmation(String email, String jobPostingTitle, String entityName) {
        gateThenDelegate(email, "conditional-selection", "sendConditionalSelectionConfirmation",
                () -> delegate.sendConditionalSelectionConfirmation(email, jobPostingTitle, entityName));
    }

    @Override
    public void sendApplicationReceived(String email, String candidateName, String jobTitle, String entityName) {
        gateThenDelegate(email, "application-received", "sendApplicationReceived",
                () -> delegate.sendApplicationReceived(email, candidateName, jobTitle, entityName));
    }

    @Override
    public void sendApplicationShortlisted(String email, String candidateName, String jobTitle, String entityName) {
        gateThenDelegate(email, "application-shortlisted", "sendApplicationShortlisted",
                () -> delegate.sendApplicationShortlisted(email, candidateName, jobTitle, entityName));
    }

    @Override
    public void sendApplicationRejected(String email, String candidateName, String jobTitle, String entityName) {
        gateThenDelegate(email, "application-rejected", "sendApplicationRejected",
                () -> delegate.sendApplicationRejected(email, candidateName, jobTitle, entityName));
    }

    @Override
    public void sendInterviewScheduled(String email, String candidateName, String jobTitle, String entityName,
                                        Instant scheduledAt, Integer durationMinutes, String interviewType,
                                        String interviewerName, String meetingUrl, String candidateNotes) {
        gateThenDelegate(email, "interview-scheduled", "sendInterviewScheduled",
                () -> delegate.sendInterviewScheduled(email, candidateName, jobTitle, entityName,
                        scheduledAt, durationMinutes, interviewType, interviewerName, meetingUrl, candidateNotes));
    }

    @Override
    public void sendInterviewReminder(String email, String candidateName, String jobTitle, String entityName,
                                       Instant scheduledAt, Integer durationMinutes, String interviewType,
                                       String interviewerName, String meetingUrl) {
        gateThenDelegate(email, "interview-reminder", "sendInterviewReminder",
                () -> delegate.sendInterviewReminder(email, candidateName, jobTitle, entityName,
                        scheduledAt, durationMinutes, interviewType, interviewerName, meetingUrl));
    }

    @Override
    public void sendOfferExtended(String email, String candidateName, String jobTitle, String entityName,
                                   BigDecimal compensationAmount, String compensationCurrency,
                                   String compensationFrequency, LocalDate startDate,
                                   Instant expiresAt, String viewOfferUrl) {
        gateThenDelegate(email, "offer-extended", "sendOfferExtended",
                () -> delegate.sendOfferExtended(email, candidateName, jobTitle, entityName,
                        compensationAmount, compensationCurrency, compensationFrequency,
                        startDate, expiresAt, viewOfferUrl));
    }

    @Override
    public void sendOfferAccepted(String email, String candidateName, String jobTitle,
                                   String entityName, LocalDate startDate) {
        gateThenDelegate(email, "offer-accepted", "sendOfferAccepted",
                () -> delegate.sendOfferAccepted(email, candidateName, jobTitle, entityName, startDate));
    }

    @Override
    public void sendOfferAcceptedToOps(String opsEmail, String candidateName, String candidateEmail,
                                        String jobTitle, String entityName, LocalDate startDate) {
        // Recipient is Ops, not the intern — gate is a no-op for staff.
        gateThenDelegate(opsEmail, "offer-accepted-to-ops", "sendOfferAcceptedToOps",
                () -> delegate.sendOfferAcceptedToOps(opsEmail, candidateName, candidateEmail,
                        jobTitle, entityName, startDate));
    }

    // ── Post-onboarding intern lifecycle (gated — internal only for ACTIVATED) ─

    @Override
    public void sendOnboardingWelcome(String email, String internName, String jobTitle,
                                       String entityName, LocalDate startDate, String dashboardUrl) {
        gateThenDelegate(email, "onboarding-welcome", "sendOnboardingWelcome",
                () -> delegate.sendOnboardingWelcome(email, internName, jobTitle, entityName,
                        startDate, dashboardUrl));
    }

    @Override
    public void sendI9Section1Reminder(String email, String internName,
                                        LocalDate section1DueDate, String dashboardUrl) {
        gateThenDelegate(email, "i9-section1-reminder", "sendI9Section1Reminder",
                () -> delegate.sendI9Section1Reminder(email, internName, section1DueDate, dashboardUrl));
    }

    @Override
    public void sendI9Section2Pending(String hrEmail, String internName,
                                       LocalDate section2DueDate, String hrDashboardUrl) {
        // Recipient is HR, not the intern — gate is a no-op for staff.
        gateThenDelegate(hrEmail, "i9-section2-pending", "sendI9Section2Pending",
                () -> delegate.sendI9Section2Pending(hrEmail, internName, section2DueDate, hrDashboardUrl));
    }

    @Override
    public void sendI983PlanNeeded(String email, String internName, String dashboardUrl) {
        gateThenDelegate(email, "i983-plan-needed", "sendI983PlanNeeded",
                () -> delegate.sendI983PlanNeeded(email, internName, dashboardUrl));
    }

    @Override
    public void sendI983PlanReady(String hrEmail, String internName, String hrDashboardUrl) {
        // Recipient is HR, not the intern — gate is a no-op for staff.
        gateThenDelegate(hrEmail, "i983-plan-ready", "sendI983PlanReady",
                () -> delegate.sendI983PlanReady(hrEmail, internName, hrDashboardUrl));
    }

    @Override
    public void sendEVerifyCaseOpened(String email, String internName, String dashboardUrl) {
        gateThenDelegate(email, "everify-case-opened", "sendEVerifyCaseOpened",
                () -> delegate.sendEVerifyCaseOpened(email, internName, dashboardUrl));
    }

    @Override
    public void sendEVerifyTncAlert(String email, String internName, String dashboardUrl) {
        gateThenDelegate(email, "everify-tnc-alert", "sendEVerifyTncAlert",
                () -> delegate.sendEVerifyTncAlert(email, internName, dashboardUrl));
    }

    @Override
    public void sendEVerifyCleared(String email, String internName, String dashboardUrl) {
        gateThenDelegate(email, "everify-cleared", "sendEVerifyCleared",
                () -> delegate.sendEVerifyCleared(email, internName, dashboardUrl));
    }

    @Override
    public void sendWorkAuthExpiryReminder(String email, String internName, int daysUntilExpiry,
                                            LocalDate expirationDate, String authType, String dashboardUrl) {
        gateThenDelegate(email, "work-auth-expiry-reminder", "sendWorkAuthExpiryReminder",
                () -> delegate.sendWorkAuthExpiryReminder(email, internName, daysUntilExpiry,
                        expirationDate, authType, dashboardUrl));
    }

    @Override
    public void sendComplianceTaskReminder(String email, String internName, String taskTitle,
                                            LocalDate dueDate, Integer daysOverdue, String dashboardUrl) {
        gateThenDelegate(email, "compliance-task-reminder", "sendComplianceTaskReminder",
                () -> delegate.sendComplianceTaskReminder(email, internName, taskTitle, dueDate,
                        daysOverdue, dashboardUrl));
    }

    @Override
    public void sendWeeklyReportDue(String email, String internName,
                                     LocalDate weekStart, String dashboardUrl) {
        gateThenDelegate(email, "weekly-report-due", "sendWeeklyReportDue",
                () -> delegate.sendWeeklyReportDue(email, internName, weekStart, dashboardUrl));
    }

    @Override
    public void sendWeeklyReportReturned(String email, String internName, LocalDate weekStart,
                                          String reviewNotes, String dashboardUrl) {
        gateThenDelegate(email, "weekly-report-returned", "sendWeeklyReportReturned",
                () -> delegate.sendWeeklyReportReturned(email, internName, weekStart, reviewNotes, dashboardUrl));
    }

    @Override
    public void sendWeeklyReportApproved(String email, String internName,
                                          LocalDate weekStart, String dashboardUrl) {
        gateThenDelegate(email, "weekly-report-approved", "sendWeeklyReportApproved",
                () -> delegate.sendWeeklyReportApproved(email, internName, weekStart, dashboardUrl));
    }

    @Override
    public void sendTimesheetDue(String email, String internName,
                                  LocalDate weekStart, String dashboardUrl) {
        gateThenDelegate(email, "timesheet-due", "sendTimesheetDue",
                () -> delegate.sendTimesheetDue(email, internName, weekStart, dashboardUrl));
    }

    @Override
    public void sendProjectAssigned(String email, String internName, String projectTitle,
                                     LocalDate dueDate, String supervisorName, String dashboardUrl) {
        gateThenDelegate(email, "project-assigned", "sendProjectAssigned",
                () -> delegate.sendProjectAssigned(email, internName, projectTitle, dueDate,
                        supervisorName, dashboardUrl));
    }

    @Override
    public void sendProjectSubmitted(String supervisorEmail, String supervisorName, String internName,
                                      String projectTitle, String supervisorDashboardUrl) {
        // Recipient is supervisor, not intern — gate is a no-op for staff.
        gateThenDelegate(supervisorEmail, "project-submitted", "sendProjectSubmitted",
                () -> delegate.sendProjectSubmitted(supervisorEmail, supervisorName, internName,
                        projectTitle, supervisorDashboardUrl));
    }

    @Override
    public void sendProjectReturned(String email, String internName, String projectTitle,
                                     String reviewNotes, String dashboardUrl) {
        gateThenDelegate(email, "project-returned", "sendProjectReturned",
                () -> delegate.sendProjectReturned(email, internName, projectTitle, reviewNotes, dashboardUrl));
    }

    @Override
    public void sendProjectCompleted(String email, String internName, String projectTitle,
                                      String dashboardUrl) {
        gateThenDelegate(email, "project-completed", "sendProjectCompleted",
                () -> delegate.sendProjectCompleted(email, internName, projectTitle, dashboardUrl));
    }

    @Override
    public void sendEvaluationDue(String supervisorEmail, String supervisorName, String internName,
                                   String evaluationType, Integer daysInDraft,
                                   String supervisorDashboardUrl) {
        // Recipient is supervisor, not intern — gate is a no-op for staff.
        gateThenDelegate(supervisorEmail, "evaluation-due", "sendEvaluationDue",
                () -> delegate.sendEvaluationDue(supervisorEmail, supervisorName, internName, evaluationType,
                        daysInDraft, supervisorDashboardUrl));
    }

    @Override
    public void sendEvaluationFinalized(String email, String internName, String evaluationType,
                                         String supervisorName, Integer overallRating,
                                         String dashboardUrl) {
        gateThenDelegate(email, "evaluation-finalized", "sendEvaluationFinalized",
                () -> delegate.sendEvaluationFinalized(email, internName, evaluationType, supervisorName,
                        overallRating, dashboardUrl));
    }

    @Override
    public void sendI983SelfEvalDue(String email, String internName, String evaluationType,
                                     String dashboardUrl) {
        gateThenDelegate(email, "i983-self-eval-due", "sendI983SelfEvalDue",
                () -> delegate.sendI983SelfEvalDue(email, internName, evaluationType, dashboardUrl));
    }

    @Override
    public void sendProjectTechApproved(String email, String internName, String projectTitle,
                                         String dashboardUrl) {
        gateThenDelegate(email, "project-tech-approved", "sendProjectTechApproved",
                () -> delegate.sendProjectTechApproved(email, internName, projectTitle, dashboardUrl));
    }

    @Override
    public void sendProjectReturnedForRevisions(String email, String internName, String projectTitle,
                                                 String reason, String dashboardUrl) {
        gateThenDelegate(email, "project-returned-for-revisions", "sendProjectReturnedForRevisions",
                () -> delegate.sendProjectReturnedForRevisions(email, internName, projectTitle, reason, dashboardUrl));
    }

    @Override
    public void sendProjectPendingViva(String email, String internName, String projectTitle,
                                        String dashboardUrl) {
        gateThenDelegate(email, "project-pending-viva", "sendProjectPendingViva",
                () -> delegate.sendProjectPendingViva(email, internName, projectTitle, dashboardUrl));
    }
}
