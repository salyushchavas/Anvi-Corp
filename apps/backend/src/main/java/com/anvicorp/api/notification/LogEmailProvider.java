package com.anvicorp.api.notification;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Fallback {@link EmailProvider}. Logs at INFO and returns. Used in dev / CI /
 * local where SMTP isn't configured. Bean is created by
 * {@link EmailProviderConfiguration} only when no SMTP host is set.
 *
 * Never throws — these are still real call sites in tests/dev, and we don't
 * want a log statement to corrupt an auth flow.
 */
@Slf4j
public class LogEmailProvider implements EmailProvider {

    @Override
    public void sendRendered(String email, String subject, String body) {
        log.info("[LOG EMAIL] To: {} | Subject: {} | Body: {}",
                email, subject,
                body == null ? "(empty)"
                        : body.length() > 200 ? body.substring(0, 200) + "…" : body);
    }

    @Override
    public void sendBrandedHtml(String email, String subject,
                                String plainBody, String htmlBody) {
        // Log the plain-text fallback; the HTML body is bulky and
        // identical-in-intent.
        log.info("[LOG EMAIL] To: {} | Subject: {} | PlainBody: {}",
                email, subject,
                plainBody == null ? "(empty)"
                        : plainBody.length() > 200 ? plainBody.substring(0, 200) + "…" : plainBody);
    }

    @Override
    public void sendVerificationCode(String email, String code, Instant expiresAt) {
        log.info("[LOG EMAIL] Verification code for {}: {} (expires {})",
                email, code, expiresAt);
    }

    @Override
    public void sendApplicantIdIssued(String email, String applicantId) {
        log.info("[LOG EMAIL] Applicant ID issued for {}: {}", email, applicantId);
    }

    @Override
    public void sendPasswordReset(String email, String code, Instant expiresAt) {
        log.info("[LOG EMAIL] Password reset code for {}: {} (expires {})",
                email, code, expiresAt);
    }

    @Override
    public void sendConditionalSelectionConfirmation(String email,
                                                     String jobPostingTitle,
                                                     String entityName) {
        log.info("[LOG EMAIL] Conditional selection confirmed for {} — {}{}",
                email,
                jobPostingTitle != null ? jobPostingTitle : "(role)",
                entityName != null ? " @ " + entityName : "");
    }

    @Override
    public void sendApplicationReceived(String email, String candidateName,
                                        String jobTitle, String entityName) {
        log.info("[LOG EMAIL] Application received for {} ({}) — {}{}",
                candidateName, email,
                jobTitle != null ? jobTitle : "(role)",
                entityName != null ? " @ " + entityName : "");
    }

    @Override
    public void sendApplicationShortlisted(String email, String candidateName,
                                           String jobTitle, String entityName) {
        log.info("[LOG EMAIL] Application shortlisted for {} ({}) — {}{}",
                candidateName, email,
                jobTitle != null ? jobTitle : "(role)",
                entityName != null ? " @ " + entityName : "");
    }

    @Override
    public void sendApplicationRejected(String email, String candidateName,
                                        String jobTitle, String entityName) {
        log.info("[LOG EMAIL] Application rejected for {} ({}) — {}{}",
                candidateName, email,
                jobTitle != null ? jobTitle : "(role)",
                entityName != null ? " @ " + entityName : "");
    }

    @Override
    public void sendInterviewScheduled(String email, String candidateName,
                                       String jobTitle, String entityName,
                                       Instant scheduledAt, Integer durationMinutes,
                                       String interviewType, String interviewerName,
                                       String meetingUrl, String candidateNotes) {
        log.info("[LOG EMAIL] Interview scheduled for {} ({}) — {} on {} ({} mins, type={}, with={}, link={})",
                candidateName, email,
                jobTitle != null ? jobTitle : "(role)",
                scheduledAt, durationMinutes, interviewType, interviewerName, meetingUrl);
    }

    @Override
    public void sendInterviewReminder(String email, String candidateName,
                                      String jobTitle, String entityName,
                                      Instant scheduledAt, Integer durationMinutes,
                                      String interviewType, String interviewerName,
                                      String meetingUrl) {
        log.info("[LOG EMAIL] Interview reminder (24h) for {} ({}) — {} on {} (with={}, link={})",
                candidateName, email,
                jobTitle != null ? jobTitle : "(role)",
                scheduledAt, interviewerName, meetingUrl);
    }

    @Override
    public void sendOfferExtended(String email, String candidateName,
                                  String jobTitle, String entityName,
                                  BigDecimal compensationAmount, String compensationCurrency,
                                  String compensationFrequency, LocalDate startDate,
                                  Instant expiresAt, String viewOfferUrl) {
        log.info("[LOG EMAIL] Offer extended to {} ({}) — {}{}, comp={} {} {}, start={}, expires={}, url={}",
                candidateName, email,
                jobTitle != null ? jobTitle : "(role)",
                entityName != null ? " @ " + entityName : "",
                compensationAmount, compensationCurrency, compensationFrequency,
                startDate, expiresAt, viewOfferUrl);
    }

    @Override
    public void sendOfferAccepted(String email, String candidateName,
                                  String jobTitle, String entityName,
                                  LocalDate startDate) {
        log.info("[LOG EMAIL] Offer accepted by {} ({}) — {}{}, start={}",
                candidateName, email,
                jobTitle != null ? jobTitle : "(role)",
                entityName != null ? " @ " + entityName : "",
                startDate);
    }

    @Override
    public void sendOfferAcceptedToOps(String opsEmail, String candidateName,
                                       String candidateEmail, String jobTitle,
                                       String entityName, LocalDate startDate) {
        log.info("[LOG EMAIL] OPS notified — offer accepted by {} <{}> for {}{}, start={} (to ops: {})",
                candidateName, candidateEmail,
                jobTitle != null ? jobTitle : "(role)",
                entityName != null ? " @ " + entityName : "",
                startDate, opsEmail);
    }

    @Override
    public void sendOnboardingWelcome(String email, String internName,
                                      String jobTitle, String entityName,
                                      LocalDate startDate, String dashboardUrl) {
        log.info("[LOG EMAIL] Onboarding welcome to {} ({}) — {}{}, start={}, dashboard={}",
                internName, email,
                jobTitle != null ? jobTitle : "(role)",
                entityName != null ? " @ " + entityName : "",
                startDate, dashboardUrl);
    }

    @Override
    public void sendI9Section1Reminder(String email, String internName,
                                       LocalDate section1DueDate, String dashboardUrl) {
        log.info("[LOG EMAIL] I-9 §1 reminder to {} ({}) — due {} dashboard={}",
                internName, email, section1DueDate, dashboardUrl);
    }

    @Override
    public void sendI9Section2Pending(String hrEmail, String internName,
                                      LocalDate section2DueDate, String hrDashboardUrl) {
        log.info("[LOG EMAIL] I-9 §2 pending → HR ({}) — intern={}, due={} dashboard={}",
                hrEmail, internName, section2DueDate, hrDashboardUrl);
    }

    @Override
    public void sendI983PlanNeeded(String email, String internName, String dashboardUrl) {
        log.info("[LOG EMAIL] I-983 plan needed to {} ({}) — dashboard={}",
                internName, email, dashboardUrl);
    }

    @Override
    public void sendI983PlanReady(String hrEmail, String internName, String hrDashboardUrl) {
        log.info("[LOG EMAIL] I-983 plan ready → HR ({}) — intern={}, dashboard={}",
                hrEmail, internName, hrDashboardUrl);
    }

    @Override
    public void sendEVerifyCaseOpened(String email, String internName, String dashboardUrl) {
        log.info("[LOG EMAIL] E-Verify case OPENED to {} ({}) — dashboard={}",
                internName, email, dashboardUrl);
    }

    @Override
    public void sendEVerifyTncAlert(String email, String internName, String dashboardUrl) {
        log.warn("[LOG EMAIL] E-Verify TNC ALERT (URGENT) to {} ({}) — dashboard={}",
                internName, email, dashboardUrl);
    }

    @Override
    public void sendEVerifyCleared(String email, String internName, String dashboardUrl) {
        log.info("[LOG EMAIL] E-Verify CLEARED to {} ({}) — dashboard={}",
                internName, email, dashboardUrl);
    }

    @Override
    public void sendWorkAuthExpiryReminder(String email, String internName,
                                           int daysUntilExpiry, LocalDate expirationDate,
                                           String authType, String dashboardUrl) {
        log.info("[LOG EMAIL] Work-auth expiry T-{}d to {} ({}) — type={} expires={} dashboard={}",
                daysUntilExpiry, internName, email, authType, expirationDate, dashboardUrl);
    }

    @Override
    public void sendComplianceTaskReminder(String email, String internName,
                                           String taskTitle, LocalDate dueDate,
                                           Integer daysOverdue, String dashboardUrl) {
        log.info("[LOG EMAIL] Compliance task reminder to {} ({}) — task=\"{}\" due={} overdue={}d dashboard={}",
                internName, email, taskTitle, dueDate, daysOverdue, dashboardUrl);
    }

    // sendWeeklyMaterialReleased + sendMaterialUnreadReminder removed in
    // Trainer Phase 0 (concept not in Trainer doc spec).

    @Override
    public void sendWeeklyReportDue(String email, String internName,
                                    LocalDate weekStart, String dashboardUrl) {
        log.info("[LOG EMAIL] Weekly report due reminder to {} ({}) — weekStart={} dashboard={}",
                internName, email, weekStart, dashboardUrl);
    }

    @Override
    public void sendWeeklyReportReturned(String email, String internName,
                                         LocalDate weekStart, String reviewNotes,
                                         String dashboardUrl) {
        log.info("[LOG EMAIL] Weekly report returned to {} ({}) — weekStart={} dashboard={}",
                internName, email, weekStart, dashboardUrl);
    }

    @Override
    public void sendWeeklyReportApproved(String email, String internName,
                                         LocalDate weekStart, String dashboardUrl) {
        log.info("[LOG EMAIL] Weekly report approved to {} ({}) — weekStart={} dashboard={}",
                internName, email, weekStart, dashboardUrl);
    }

    @Override
    public void sendTimesheetDue(String email, String internName,
                                 LocalDate weekStart, String dashboardUrl) {
        log.info("[LOG EMAIL] Timesheet due reminder to {} ({}) — weekStart={} dashboard={}",
                internName, email, weekStart, dashboardUrl);
    }

    @Override
    public void sendProjectAssigned(String email, String internName,
                                    String projectTitle, LocalDate dueDate,
                                    String supervisorName, String dashboardUrl) {
        log.info("[LOG EMAIL] Project assigned to {} ({}) — \"{}\" due={} by={} dashboard={}",
                internName, email, projectTitle, dueDate, supervisorName, dashboardUrl);
    }

    @Override
    public void sendProjectSubmitted(String supervisorEmail, String supervisorName,
                                     String internName, String projectTitle,
                                     String supervisorDashboardUrl) {
        log.info("[LOG EMAIL] Project submitted → supervisor ({}) — by {}, \"{}\" dashboard={}",
                supervisorEmail, internName, projectTitle, supervisorDashboardUrl);
    }

    @Override
    public void sendProjectReturned(String email, String internName,
                                    String projectTitle, String reviewNotes,
                                    String dashboardUrl) {
        log.info("[LOG EMAIL] Project returned to {} ({}) — \"{}\" dashboard={}",
                internName, email, projectTitle, dashboardUrl);
    }

    @Override
    public void sendProjectCompleted(String email, String internName,
                                     String projectTitle, String dashboardUrl) {
        log.info("[LOG EMAIL] Project completed to {} ({}) — \"{}\" dashboard={}",
                internName, email, projectTitle, dashboardUrl);
    }

    @Override
    public void sendEvaluationDue(String supervisorEmail, String supervisorName,
                                  String internName, String evaluationType,
                                  Integer daysInDraft, String supervisorDashboardUrl) {
        log.info("[LOG EMAIL] Evaluation due → supervisor ({}) — intern={} type={} draftAge={}d dashboard={}",
                supervisorEmail, internName, evaluationType, daysInDraft, supervisorDashboardUrl);
    }

    @Override
    public void sendEvaluationFinalized(String email, String internName,
                                        String evaluationType, String supervisorName,
                                        Integer overallRating, String dashboardUrl) {
        log.info("[LOG EMAIL] Evaluation finalized to {} ({}) — type={} by={} rating={} dashboard={}",
                internName, email, evaluationType, supervisorName, overallRating, dashboardUrl);
    }

    @Override
    public void sendI983SelfEvalDue(String email, String internName,
                                    String evaluationType, String dashboardUrl) {
        log.info("[LOG EMAIL] I-983 self-eval due to {} ({}) — type={} dashboard={}",
                internName, email, evaluationType, dashboardUrl);
    }

    @Override
    public void sendProjectTechApproved(String email, String internName,
                                        String projectTitle, String dashboardUrl) {
        log.info("[LOG EMAIL] Project tech-approved → {} ({}) — \"{}\" dashboard={}",
                internName, email, projectTitle, dashboardUrl);
    }

    @Override
    public void sendProjectReturnedForRevisions(String email, String internName,
                                                String projectTitle, String reason,
                                                String dashboardUrl) {
        log.info("[LOG EMAIL] Project returned for revisions → {} ({}) — \"{}\" reason={} dashboard={}",
                internName, email, projectTitle, reason, dashboardUrl);
    }

    @Override
    public void sendProjectPendingViva(String email, String internName,
                                       String projectTitle, String dashboardUrl) {
        log.info("[LOG EMAIL] Project pending viva → {} ({}) — \"{}\" dashboard={}",
                internName, email, projectTitle, dashboardUrl);
    }
}
